package org.openrndr.plugin.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * File | New | New OPENRNDR Project... (OPENRNDR Mode only): copies the OPENRNDR template
 * into a folder the user picks, gives it a fresh git repository, then opens it.
 */
class NewOpenrndrProjectAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = OpenrndrModeAction.isOn
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dialog = LocationDialog(e.project)
        if (!dialog.showAndGet()) return
        clone(e.project, dialog.target)
    }

    private fun clone(project: Project?, target: Path) {
        object : Task.Backgroundable(project, "Creating OPENRNDR project", true) {
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "git clone $TEMPLATE_URL"
                // Only the latest snapshot is needed: the template's history is replaced below
                if (!git(indicator, null, "clone", "--depth", "1", TEMPLATE_URL, target.toString())) return

                // Start the project with a fresh repository of its own instead of the template's
                indicator.text = "git init"
                if (!target.resolve(".git").toFile().deleteRecursively()) {
                    error = "Could not remove the template's git history in $target."
                    return
                }
                git(indicator, target, "init")
            }

            /** Runs git; on failure records [error] and returns false. */
            private fun git(indicator: ProgressIndicator, dir: Path?, vararg args: String): Boolean {
                // CONSOLE environment: the user's shell PATH, so the same git as in a terminal
                val command = GeneralCommandLine("git", *args)
                    .withWorkingDirectory(dir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                val output = try {
                    CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator)
                } catch (ex: Exception) {
                    error = "Could not run git: ${ex.message}"
                    return false
                }
                error = when {
                    output.isCancelled -> "Creating the project was cancelled."
                    output.exitCode != 0 -> output.stderr.trim().ifEmpty { "git ${args[0]} failed (exit code ${output.exitCode})." }
                    else -> null
                }
                return error == null
            }

            override fun onSuccess() {
                error?.let { return notifyError(project, it) }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                ProjectUtil.openOrImport(target, project, false)
            }
        }.queue()
    }

    private fun notifyError(project: Project?, text: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OPENRNDR Mode")
            .createNotification("New OPENRNDR project failed", text, NotificationType.ERROR)
            .notify(project)
    }

    /** Asks for the project name and the folder to create it in. */
    private class LocationDialog(project: Project?) : DialogWrapper(project) {
        private val name = JBTextField("openrndr-sketch", 30)
        private val parent = TextFieldWithBrowseButton().apply {
            text = ProjectUtil.getBaseDir()
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Choose Where to Create the Project"))
        }

        val target: Path get() = Path.of(parent.text.trim()).resolve(name.text.trim())

        init {
            title = "New OPENRNDR Project"
            setOKButtonText("Create")
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", name)
            .addLabeledComponent("Location:", parent)
            .addComponentToRightColumn(JBLabel("The OPENRNDR template is cloned from $TEMPLATE_URL"))
            .panel

        override fun getPreferredFocusedComponent() = name

        override fun doValidate(): ValidationInfo? = when {
            name.text.isBlank() -> ValidationInfo("Enter a project name", name)
            parent.text.isBlank() -> ValidationInfo("Choose a location", parent)
            Files.exists(target) -> ValidationInfo("$target already exists", name)
            else -> null
        }
    }

    private companion object {
        const val TEMPLATE_URL = "https://github.com/openrndr/openrndr-template"
    }
}
