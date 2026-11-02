package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Edits the project settings of an openrndr-template project: name, group and version and the snapshot
 * toggles in gradle.properties, and the openrndr and orx versions in gradle/libs.versions.toml.
 * The files are edited line by line, so comments and everything else in them stay as they are.
 * After a change the Gradle project is reloaded.
 */
class ProjectSettingsAction : DumbAwareAction(AllIcons.General.Settings) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { propertiesFile(it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val properties = propertiesFile(project) ?: return
        val versions = project.guessProjectDir()?.findFileByRelativePath(VERSIONS_PATH)

        val before = Settings.read(text(properties), versions?.let { text(it) })
        val dialog = SettingsDialog(project, before.copy(), versions != null)
        if (!dialog.showAndGet() || dialog.settings == before) return
        val after = dialog.settings

        WriteCommandAction.writeCommandAction(project).withName("Edit Project Settings").run<Exception> {
            edit(properties) { text ->
                text.setProperty(NAME, after.name, before.name)
                    .setProperty(GROUP, after.group, before.group)
                    .setProperty(VERSION, after.version, before.version)
                    .setProperty(SONATYPE, after.sonatypeSnapshots.toString(), before.sonatypeSnapshots.toString())
                    .setProperty(LOCAL, after.localSnapshots.toString(), before.localSnapshots.toString())
            }
            if (versions != null) edit(versions) { text ->
                text.setCatalogVersion(OPENRNDR, after.openrndr).setCatalogVersion(ORX, after.orx)
            }
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        reloadGradle(project)
    }

    private data class Settings(
        var name: String,
        var group: String,
        var version: String,
        var openrndr: String,
        var orx: String,
        var sonatypeSnapshots: Boolean,
        var localSnapshots: Boolean,
    ) {
        companion object {
            fun read(properties: String, versions: String?) = Settings(
                name = properties.property(NAME).orEmpty(),
                group = properties.property(GROUP).orEmpty(),
                version = properties.property(VERSION).orEmpty(),
                openrndr = versions?.catalogVersion(OPENRNDR).orEmpty(),
                orx = versions?.catalogVersion(ORX).orEmpty(),
                sonatypeSnapshots = properties.property(SONATYPE).toBoolean(),
                localSnapshots = properties.property(LOCAL).toBoolean(),
            )
        }
    }

    private class SettingsDialog(project: Project, val settings: Settings, private val hasVersions: Boolean) : DialogWrapper(project) {
        init {
            title = "OPENRNDR Project Settings"
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            group("gradle.properties") {
                row("Name:") { textField().bindText(settings::name).align(AlignX.FILL).focused().validationOnApply { required(it.text) } }
                row("Group:") { textField().bindText(settings::group).align(AlignX.FILL).validationOnApply { required(it.text) } }
                row("Version:") { textField().bindText(settings::version).align(AlignX.FILL).validationOnApply { required(it.text) } }
                row { checkBox("Allow Sonatype snapshots").bindSelected(settings::sonatypeSnapshots) }
                row { checkBox("Allow local snapshots (Maven local)").bindSelected(settings::localSnapshots) }
            }
            group(VERSIONS_PATH) {
                row("OPENRNDR version:") { textField().bindText(settings::openrndr).align(AlignX.FILL).validationOnApply { version(it.text) } }
                row("orx version:") { textField().bindText(settings::orx).align(AlignX.FILL).validationOnApply { version(it.text) } }
            }.enabled(hasVersions)
        }

        private fun required(text: String) = if (text.isBlank()) error("Enter a value") else null
        private fun version(text: String) = when {
            !hasVersions -> null
            text.isBlank() -> error("Enter a version")
            '"' in text || text.any { it.isWhitespace() } -> error("Quotes and spaces are not allowed in a version")
            else -> null
        }
    }

    private companion object {
        const val VERSIONS_PATH = "gradle/libs.versions.toml"
        const val NAME = "project.name"
        const val GROUP = "project.group"
        const val VERSION = "project.version"
        const val SONATYPE = "openrndr.allowSonatypeSnapshots"
        const val LOCAL = "openrndr.allowLocalSnapshots"
        const val OPENRNDR = "openrndr"
        const val ORX = "orx"

        /** Gradle's own system id; avoids a dependency on the Gradle plugin. */
        val GRADLE = ProjectSystemId("GRADLE")

        fun propertiesFile(project: Project) = project.guessProjectDir()?.findChild("gradle.properties")

        fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)?.text ?: String(file.contentsToByteArray(), file.charset)

        fun edit(file: VirtualFile, change: (String) -> String) {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return
            val changed = change(document.text)
            if (changed != document.text) document.setText(changed)
        }

        fun propertyRegex(key: String) = Regex("""(?m)^(\s*${Regex.escape(key)}\s*[=:]\s*)(.*?)\s*$""")

        fun String.property(key: String): String? = propertyRegex(key).find(this)?.groupValues?.get(2)

        /** Replaces the value of [key], or appends the property when it is missing and the value changed. */
        fun String.setProperty(key: String, value: String, old: String): String {
            if (value == old) return this
            val regex = propertyRegex(key)
            if (regex.containsMatchIn(this)) return regex.replace(this) { it.groupValues[1] + value }
            return removeSuffix("\n") + "\n$key=$value\n"
        }

        fun catalogRegex(key: String) = Regex("""(?m)^(\s*${Regex.escape(key)}\s*=\s*")([^"]*)(")""")

        /** The version of [key] in the `[versions]` table, the first table in the template's catalog. */
        fun String.catalogVersion(key: String): String? = versionsTable()?.let { catalogRegex(key).find(it)?.groupValues?.get(2) }

        fun String.setCatalogVersion(key: String, value: String): String {
            val table = versionsTable() ?: return this
            val start = indexOf(table)
            val changed = catalogRegex(key).replace(table) { it.groupValues[1] + value + it.groupValues[3] }
            return replaceRange(start, start + table.length, changed)
        }

        fun String.versionsTable(): String? =
            Regex("""(?ms)^\[versions]\s*$.*?(?=^\[|\z)""").find(this)?.value

        fun reloadGradle(project: Project) {
            val path = project.basePath ?: return
            ExternalSystemUtil.refreshProject(path, ImportSpecBuilder(project, GRADLE))
        }
    }
}
