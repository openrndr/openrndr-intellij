package org.openrndr.plugin.intellij

import com.intellij.ide.ui.UISettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager

/**
 * View | OPENRNDR Mode (Ctrl+Alt+P).
 *
 * ON:  hides every tool window except Run, hides navigation bar / breadcrumbs / tool window stripes, strips the main toolbar down
 *      to Search Everywhere plus sketch buttons (play runs only the current file), and hides the
 *      Navigate, Code, Refactor, Build, Run, Tools and Git menus; File | New only offers
 *      New OPENRNDR Project.
 * OFF: restores the UI settings that were active before.
 */
class OpenrndrModeAction : ToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = isOn

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        if (state) enable(project) else disable(project)
    }

    private fun enable(project: Project) {
        val ui = UISettings.getInstance()
        val editor = EditorSettingsExternalizable.getInstance()

        Snapshot.saved = Snapshot(
            showNavigationBar = ui.showNavigationBar,
            hideToolStripes = ui.hideToolStripes,
            breadcrumbs = editor.isBreadcrumbsShown,
            openToolWindows = openToolWindowIds(project),
        )

        // 1. Chrome: no nav bar, no side stripes, no breadcrumbs
        ui.showNavigationBar = false
        ui.hideToolStripes = true
        editor.isBreadcrumbsShown = false
        ui.fireUISettingsChanged()

        // 2. Only the editor and the Run console remain
        val twm = ToolWindowManager.getInstance(project)
        twm.toolWindowIds
            .filter { it != ToolWindowId.RUN }
            .forEach { twm.getToolWindow(it)?.hide() }

        // 3. Main toolbar and menu bar: only what a sketch needs
        ActionGroupHider.updateAll()

        notify(project, "OPENRNDR Mode on. Press Ctrl+Alt+P again to restore your layout.")
    }

    private fun disable(project: Project) {
        val snap = Snapshot.saved ?: return
        val ui = UISettings.getInstance()
        val editor = EditorSettingsExternalizable.getInstance()

        ui.showNavigationBar = snap.showNavigationBar
        ui.hideToolStripes = snap.hideToolStripes
        editor.isBreadcrumbsShown = snap.breadcrumbs
        ui.fireUISettingsChanged()

        val twm = ToolWindowManager.getInstance(project)
        snap.openToolWindows.forEach { twm.getToolWindow(it)?.show() }

        Snapshot.saved = null
        ActionGroupHider.updateAll()
    }

    private fun openToolWindowIds(project: Project): List<String> {
        val twm = ToolWindowManager.getInstance(project)
        return twm.toolWindowIds.filter { twm.getToolWindow(it)?.isVisible == true }
    }

    private fun notify(project: Project, text: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OPENRNDR Mode")
            .createNotification(text, NotificationType.INFORMATION)
            .notify(project)
    }

    companion object {
        val isOn: Boolean get() = Snapshot.saved != null
    }

    /** What the IDE looked like before OPENRNDR Mode was switched on (kept for this IDE session). */
    private data class Snapshot(
        val showNavigationBar: Boolean,
        val hideToolStripes: Boolean,
        val breadcrumbs: Boolean,
        val openToolWindows: List<String>,
    ) {
        companion object {
            @Volatile var saved: Snapshot? = null
        }
    }
}
