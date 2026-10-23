package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.guessProjectDir
import java.nio.file.Path

/** Opens the project root in the system file manager (Finder on macOS). */
class ShowProjectFolderAction : DumbAwareAction(AllIcons.Actions.MenuOpen) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = RevealFileAction.isSupported() && e.project?.guessProjectDir() != null
        e.presentation.text = "Show Project Folder in ${RevealFileAction.getFileManagerName()}"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dir = e.project?.guessProjectDir() ?: return
        RevealFileAction.openDirectory(Path.of(dir.path))
    }
}

/** A main toolbar group that only shows while OPENRNDR Mode is on. */
class OpenrndrModeGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = OpenrndrModeAction.isOn
    }
}
