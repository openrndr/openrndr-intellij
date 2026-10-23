package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware

/**
 * Main toolbar group next to play/stop in OPENRNDR Mode: Reformat Code, Comment with Line Comment
 * and Rename. These are the platform's own actions, so they act on the editor exactly like their shortcuts.
 */
class SketchEditGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = OpenrndrModeAction.isOn
    }

    private val children: Array<AnAction> by lazy {
        val am = ActionManager.getInstance()
        listOfNotNull(
            Separator.create(),
            am.getAction("ReformatCode"),
            am.getAction("CommentByLineComment"),
            am.getAction("RenameElement")?.let(::WithIcon),
        ).toTypedArray()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children

    /** Rename has no icon of its own, and a toolbar button needs one. */
    private class WithIcon(action: AnAction) : AnActionWrapper(action) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.icon = AllIcons.Actions.Edit
        }
    }
}
