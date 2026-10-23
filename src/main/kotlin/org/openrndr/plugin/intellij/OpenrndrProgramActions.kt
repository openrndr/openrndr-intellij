package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/** Helpers shared by the OPENRNDR program buttons. */
object Openrndr {
    const val TEMPLATE = "OPENRNDR Program"

    /** True when the project depends on an `org.openrndr` library; cached until the project's dependencies change. */
    fun isProject(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            var found = false
            OrderEnumerator.orderEntries(project).librariesOnly().forEachLibrary { library ->
                found = library.name?.contains("org.openrndr") == true
                !found
            }
            CachedValueProvider.Result.create(found, ProjectRootManager.getInstance(project))
        }

    private val APPLICATION = Regex("""\bapplication\s*\{""")

    /** The file selected in the editor, if it is an OPENRNDR program (a Kotlin file with an `application { }` block). */
    fun currentProgram(project: Project): VirtualFile? {
        val file = FileEditorManager.getInstance(project).selectedEditor?.file ?: return null
        if (file.extension != "kt") return null
        val text = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return null
        return file.takeIf { text.contains("org.openrndr") && APPLICATION.containsMatchIn(text) }
    }

    /** Where a new program goes: next to the current file if it is in a source folder, else the main Kotlin source folder. */
    fun targetDirectory(project: Project): VirtualFile? {
        val index = ProjectRootManager.getInstance(project).fileIndex
        FileEditorManager.getInstance(project).selectedEditor?.file?.parent
            ?.takeIf { index.isInSourceContent(it) && !index.isInTestSourceContent(it) }
            ?.let { return it }
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.filterNot { index.isInTestSourceContent(it) }
        return roots.firstOrNull { it.path.endsWith("src/main/kotlin") } ?: roots.firstOrNull()
    }

    /**
     * The next free name after [name]: a trailing number goes up by one, keeping its zero padding
     * (Sketch01 -> Sketch02, Sketch09 -> Sketch10), and a name without one gets 01 (Sketch -> Sketch01).
     */
    fun nextName(name: String, dir: VirtualFile): String {
        val match = Regex("""^(.*?)(\d+)$""").find(name)
        val base = match?.groupValues?.get(1) ?: name
        var digits = match?.groupValues?.get(2) ?: "00"
        while (true) {
            digits = (digits.toLong() + 1).toString().padStart(digits.length, '0')
            val candidate = base + digits
            if (dir.findChild("$candidate.kt") == null) return candidate
        }
    }

    /** Asks for a program name; null when cancelled. Dashes and spaces are refused, the first letter is made uppercase. */
    fun askName(project: Project, title: String, dir: VirtualFile, suggestion: String): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(input: String): String? {
                val name = clean(input)
                return when {
                    name.isEmpty() -> "Enter a name"
                    '-' in name -> "Dashes are not allowed in a program name"
                    name.any { it.isWhitespace() } -> "Spaces are not allowed in a program name"
                    dir.findChild("${capitalize(name)}.kt") != null -> "${capitalize(name)}.kt already exists"
                    else -> null
                }
            }
        }
        val input = Messages.showInputDialog(
            project,
            "Program name (dashes and spaces are not allowed; the first letter becomes uppercase):",
            title, null, suggestion, validator,
        ) ?: return null
        return capitalize(clean(input))
    }

    /** Opens [file] in the editor. */
    fun open(project: Project, file: VirtualFile) {
        OpenFileDescriptor(project, file).navigate(true)
    }

    private fun clean(input: String) = input.trim().removeSuffix(".kt")
    private fun capitalize(name: String) = name.replaceFirstChar { it.uppercaseChar() }
}

/** Main toolbar group with the OPENRNDR program buttons and the guide; only shown in OPENRNDR Mode in an OPENRNDR project. */
class OpenrndrProgramGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && OpenrndrModeAction.isOn && Openrndr.isProject(project)
    }
}

/** Creates a new OPENRNDR program from the bundled template. */
class NewOpenrndrProgramAction : DumbAwareAction(AllIcons.General.Add) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { Openrndr.targetDirectory(it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = Openrndr.targetDirectory(project) ?: return
        val name = Openrndr.askName(project, "New OPENRNDR Program", dir, "") ?: return
        val psiDir: PsiDirectory = PsiManager.getInstance(project).findDirectory(dir) ?: return
        val template = FileTemplateManager.getInstance(project).getInternalTemplate(Openrndr.TEMPLATE)
        // Fills in the package for the target folder, like File | New does
        val created = FileTemplateUtil.createFromTemplate(template, name, null, psiDir)
        created.containingFile?.virtualFile?.let { Openrndr.open(project, it) }
    }
}

/** Copies the current OPENRNDR program into a new file, suggesting the next numbered name. */
class DuplicateOpenrndrProgramAction : DumbAwareAction(AllIcons.Actions.Copy) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { Openrndr.currentProgram(it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = Openrndr.currentProgram(project) ?: return
        val dir = source.parent ?: return
        val name = Openrndr.askName(project, "Duplicate OPENRNDR Program", dir, Openrndr.nextName(source.nameWithoutExtension, dir)) ?: return
        // The editor's text, so unsaved changes are copied too
        val text = FileDocumentManager.getInstance().getDocument(source)?.text ?: return
        val copy = WriteCommandAction.writeCommandAction(project).withName("Duplicate Program").compute<VirtualFile, Exception> {
            dir.createChildData(this, "$name.kt").also { VfsUtil.saveText(it, text) }
        }
        Openrndr.open(project, copy)
    }
}
