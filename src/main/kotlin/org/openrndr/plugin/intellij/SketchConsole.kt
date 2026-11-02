package org.openrndr.plugin.intellij

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.actions.ScrollToTheEndToolbarAction
import com.intellij.openapi.editor.actions.ToggleUseSoftWrapsToolbarAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/**
 * Console for sketches run with the play button. It has no side toolbar (stack trace navigation,
 * soft wrap, scroll to end, print, clear); soft wrap, scroll to end and clear move to the run's
 * top toolbar through [topActions] instead.
 */
class SketchConsole(project: Project, scope: GlobalSearchScope) : ConsoleViewImpl(project, scope, false, true) {

    override fun createConsoleActions(): Array<AnAction> = AnAction.EMPTY_ARRAY

    /**
     * Soft wrap, scroll to end and clear, for the run's top toolbar. The platform's console actions
     * need the console editor, which only exists once the console is shown, so they are fetched
     * the first time the toolbar asks for them.
     */
    val topActions: ActionGroup = object : ActionGroup() {
        private var actions: Array<AnAction>? = null

        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            actions?.let { return it }
            if (editor == null) return AnAction.EMPTY_ARRAY
            val platform = super@SketchConsole.createConsoleActions()
            return listOfNotNull(
                platform.firstOrNull { it is ToggleUseSoftWrapsToolbarAction },
                platform.firstOrNull { it is ScrollToTheEndToolbarAction },
                clearThisConsoleAction(),
            ).toTypedArray().also { actions = it }
        }
    }
}
