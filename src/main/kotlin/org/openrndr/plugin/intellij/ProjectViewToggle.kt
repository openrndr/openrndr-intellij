package org.openrndr.plugin.intellij

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager

/** Shows and hides the Project tool window. */
class ToggleProjectViewAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.isVisible == true
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
        if (state) window.activate(null) else window.hide()
    }
}

/**
 * In OPENRNDR Mode the project tree of an OPENRNDR project only shows what a sketch needs: the project folder
 * with just [VISIBLE] in it, and no External Libraries or Scratches. The folders themselves show everything.
 */
class OpenrndrProjectViewFilter : TreeStructureProvider, DumbAware {

    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        if (!OpenrndrModeAction.isOn) return children
        val project = parent.project ?: return children
        if (project.isDisposed || !Openrndr.isProject(project)) return children
        val root = project.guessProjectDir() ?: return children

        return when {
            // The tree's root: keep only the project folder, if it is there
            parent.value is Project -> {
                val folder = children.filter { (it as? ProjectViewNode<*>)?.virtualFile == root }
                folder.ifEmpty { children }
            }
            (parent as? ProjectViewNode<*>)?.virtualFile == root ->
                children.filter { (it as? ProjectViewNode<*>)?.virtualFile?.name in VISIBLE }
            else -> children
        }
    }

    companion object {
        val VISIBLE = setOf("data", "src", "gradle", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")

        /** Rebuilds the open project trees, after OPENRNDR Mode was switched on or off. */
        fun refreshAll() {
            ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .forEach { ProjectView.getInstance(it).refresh() }
        }
    }
}
