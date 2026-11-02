package org.openrndr.plugin.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsFileListenerIgnoredFilesProvider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.nio.file.Path
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a program from the OPENRNDR guide: the green ▶ the guide tab adds next to the copy button of each code block
 * with a `fun main`.
 *
 * The code goes into `src/main/kotlin/guide/<PageTitle><NN><letter>.kt`, NN being the block's position on the page.
 * A copy that still matches the guide is run again; once the user has edited it, the next click makes a copy with the
 * next letter, so edits are never overwritten. The copies are left out of version control (no "Add to Git?" question;
 * the user can add the ones worth keeping). orx modules the code needs and the project lacks are added to
 * `build.gradle.kts` first, followed by a Gradle reload.
 */
object GuidePrograms {
    const val PACKAGE = "guide"

    private val GRADLE = ProjectSystemId("GRADLE")

    /** The paths of the copies made since the IDE started, for [VcsFilter]. */
    private val created: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Handles a click on the ▶ of code block [index] (from 1) on the guide page titled [title]. */
    fun run(project: Project, title: String, index: Int, code: String) {
        val module = openrndrModule(project)
        val root = module?.let { sourceRoot(it) }
        if (module == null || root == null) {
            return notify(project, "Open an OPENRNDR project to run guide programs. Create one with File | New | New OPENRNDR Project.", NotificationType.WARNING)
        }
        val text = programText(code)
        val name = baseName(title, index)

        val missing = OrxModules.missing(module, OrxModules.imported(code))
        if (missing.isEmpty()) return writeAndRun(project, root, name, text)

        // The modules go in first: a program with unresolved imports would stop the whole module from compiling
        val error = addModules(project, module, missing)
        if (error != null) return notify(project, error, NotificationType.WARNING)
        reloadGradle(project, module, missing) { writeAndRun(project, root, name, text) }
    }

    /** The file name, without letter, for code block [index] on the page titled [title]: `DrawingCirclesRectanglesAndLines01`. */
    fun baseName(title: String, index: Int): String {
        val page = title.replace(Regex("""\s*\|\s*OPENRNDR GUIDE\s*$""", RegexOption.IGNORE_CASE), "")
        val ascii = Normalizer.normalize(page, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        val words = ascii.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        var name = words.joinToString("") { word -> word.replaceFirstChar { it.uppercaseChar() } }
        if (name.isEmpty() || name.first().isDigit()) name = "Guide$name"
        return name + index.toString().padStart(2, '0')
    }

    /** The letters after the number: a, b, …, z, aa, ab, … */
    fun suffixes(): Sequence<String> = generateSequence(0) { it + 1 }.map { n ->
        var i = n
        buildString {
            do {
                insert(0, 'a' + i % 26)
                i = i / 26 - 1
            } while (i >= 0)
        }
    }

    /** The file's text for the guide's [code]: in the `guide` package, with Unix line endings and one final newline. */
    fun programText(code: String): String {
        val body = StringUtil.convertLineSeparators(code)
            .replace(Regex("""^\s*package\s+[\w.]+\s*\n"""), "")
            .trimStart('\n')
            .trimEnd()
        return "package $PACKAGE\n\n$body\n"
    }

    /** True when [current] is still [text]: whitespace at line ends and blank lines at the ends do not count as edits. */
    fun isUnchanged(current: CharSequence, text: String): Boolean {
        fun normal(s: CharSequence) = StringUtil.convertLineSeparators(s.toString()).lines().joinToString("\n") { it.trimEnd() }.trim()
        return normal(current) == normal(text)
    }

    /**
     * The file in [dir] to run for [name] with [text]: the first copy that still matches (comparing the editor's text,
     * so unsaved edits count), else a new file with the first free letter. Call in a write action.
     */
    fun findOrCreate(dir: VirtualFile, name: String, text: String): VirtualFile {
        val documents = FileDocumentManager.getInstance()
        for (suffix in suffixes()) {
            val file = dir.findChild("$name$suffix.kt")
            if (file == null) {
                // Recorded first: version control looks at the file as soon as it exists
                created += "${dir.path}/$name$suffix.kt"
                return dir.createChildData(this, "$name$suffix.kt").also { VfsUtil.saveText(it, text) }
            }
            val current = documents.getDocument(file)?.immutableCharSequence ?: continue
            if (isUnchanged(current, text)) return file
        }
        error("unreachable")
    }

    /**
     * Keeps version control from asking whether to add the copies, and from adding them: they stay unversioned until
     * the user adds them. Files the user creates, also in the `guide` folder, are not affected.
     */
    class VcsFilter : VcsFileListenerIgnoredFilesProvider {
        override fun isAdditionIgnored(project: Project, filePath: FilePath) = filePath.path in created
    }

    private fun writeAndRun(project: Project, root: VirtualFile, name: String, text: String) {
        val file = WriteCommandAction.writeCommandAction(project).withName("Copy Guide Program").compute<VirtualFile, Exception> {
            findOrCreate(VfsUtil.createDirectoryIfMissing(root, PACKAGE), name, text)
        }
        Openrndr.open(project, file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // Waits for the new file, and libraries from a Gradle reload, to be indexed before looking for the main function
        RunSketchAction.run(project, file) {
            notify(project, "${file.name} has no main function the IDE can run.", NotificationType.WARNING)
        }
    }

    /** The project's module that depends on OPENRNDR and has a main Kotlin source folder. */
    private fun openrndrModule(project: Project): Module? =
        ModuleManager.getInstance(project).modules.filter { usesOpenrndr(it) && sourceRoot(it) != null }
            // Gradle makes a module per source set: prefer `main`
            .let { modules -> modules.firstOrNull { it.name.endsWith(".main") } ?: modules.firstOrNull() }

    private fun usesOpenrndr(module: Module): Boolean {
        var found = false
        OrderEnumerator.orderEntries(module).librariesOnly().forEachLibrary { library ->
            found = library.name?.contains("org.openrndr") == true
            !found
        }
        return found
    }

    /** The module's `src/main/kotlin`, or another of its (non-test) source folders. */
    private fun sourceRoot(module: Module): VirtualFile? {
        val roots = ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE)
        return roots.firstOrNull { it.path.endsWith("src/main/kotlin") } ?: roots.firstOrNull()
    }

    /** Adds [missing] to the project's build.gradle.kts; the reason when it cannot. */
    private fun addModules(project: Project, module: Module, missing: List<OrxModules.OrxModule>): String? {
        val names = missing.joinToString { it.artifact }
        val needs = "This program needs $names, which this project does not include."
        val path = ExternalSystemApiUtil.getExternalRootProjectPath(module) ?: project.basePath ?: return needs
        val buildFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path, "build.gradle.kts"))
        val document = buildFile?.let { FileDocumentManager.getInstance().getDocument(it) }
        val unsupported = "$needs It can only be added automatically to projects made from the current OPENRNDR template, " +
            "which lists orx modules as implementation(orx.…) in build.gradle.kts. " +
            "Create one with File | New | New OPENRNDR Project."
        if (document == null) return unsupported

        // The catalog of the project's own orx version, when Gradle has it: a key it lacks would break the build script
        val version = OrxModules.projectVersion(Path.of(path))
        val catalog = version?.let { OrxModules.catalog(it) }
        val unavailable = missing.filter { catalog != null && it.artifact !in catalog }
        if (unavailable.isNotEmpty()) {
            return "This program needs ${unavailable.joinToString { it.artifact }}, which orx $version (the version this project uses) does not have."
        }
        val keys = missing.map { catalog?.get(it.artifact) ?: it.key }

        val text = OrxBuildFile.withModules(document.text, keys) ?: return unsupported
        // Unchanged when the lines are there already and only the reload is missing
        if (text == document.text) return null
        WriteCommandAction.writeCommandAction(project).withName("Add orx Modules").run<Exception> {
            document.setText(text)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        return null
    }

    /** Reloads the Gradle project once build.gradle.kts includes [added], then calls [then] if that worked. */
    private fun reloadGradle(project: Project, module: Module, added: List<OrxModules.OrxModule>, then: () -> Unit) {
        val names = added.joinToString { it.artifact }
        val path = ExternalSystemApiUtil.getExternalRootProjectPath(module)
        if (path == null || ExternalSystemApiUtil.getManager(GRADLE) == null) {
            return notify(project, "build.gradle.kts now includes $names. Reload the Gradle project, then run the program again.", NotificationType.INFORMATION)
        }
        notify(project, "build.gradle.kts now includes $names. Reloading the Gradle project…", NotificationType.INFORMATION)
        // withCallback, unlike callback, keeps importing the result into the project before calling back
        val spec = ImportSpecBuilder(project, GRADLE).withCallback { success: Boolean ->
            ApplicationManager.getApplication().invokeLater({
                if (success) then()
                else notify(project, "The Gradle reload with $names failed; see the Build window.", NotificationType.ERROR)
            }, project.disposed)
        }
        ExternalSystemUtil.refreshProject(path, spec)
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OPENRNDR Guide")
            .createNotification(text, type)
            .notify(project)
    }
}
