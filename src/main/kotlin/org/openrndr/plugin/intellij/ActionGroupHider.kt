package org.openrndr.plugin.intellij

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * Takes actions out of registered action groups while OPENRNDR Mode is on, and puts every one
 * back in its original order when the mode is switched off. Nothing is persisted.
 */
class ActionGroupHider(private val groups: List<String>, private val hide: (actionId: String?) -> Boolean) {

    /** Each group's children from before OPENRNDR Mode, so they can be restored as they were. */
    private val saved = mutableMapOf<String, List<AnAction>>()

    fun update() {
        val am = ActionManager.getInstance()
        for (id in groups) {
            val group = am.getAction(id) as? DefaultActionGroup ?: continue
            if (OpenrndrModeAction.isOn) {
                if (id in saved) continue
                val children = group.childActionsOrStubs.toList()
                saved[id] = children
                children.filter { hide(am.getId(it)) }.forEach { group.remove(it) }
            } else {
                val children = saved.remove(id) ?: continue
                group.removeAll()
                children.forEach { group.add(it) }
            }
        }
    }

    companion object {
        /**
         * Main toolbar: only Search Everywhere, [RunSketchAction], [StopSketchAction], the OPENRNDR
         * program buttons, [SketchEditGroup] and the project folder button stay; the project and VCS
         * widgets, file name, run widget and plugin widgets go.
         */
        private val toolbar = ActionGroupHider(listOf("MainToolbarLeft", "MainToolbarCenter", "MainToolbarRight")) {
            it !in setOf(
                "SearchEverywhere",
                "Openrndr.RunSketch",
                "Openrndr.StopSketch",
                "Openrndr.ProgramGroup",
                "Openrndr.EditGroup",
                "Openrndr.FolderGroup",
            )
        }

        /** Menu bar: Navigate, Code, Refactor, Build, Run, Tools and Git (VCS) go. */
        private val menu = ActionGroupHider(listOf("MainMenu")) {
            it in setOf("GoToMenu", "CodeMenu", "RefactoringMenu", "BuildMenu", "RunMenu", "ToolsMenu", "VcsGroups")
        }

        /** File | New: only New OPENRNDR Project. File | New shows these two groups combined. */
        private val newMenu = ActionGroupHider(listOf("NewProjectOrModuleGroup", "NewGroup")) {
            it != "Openrndr.NewOpenrndrProject"
        }

        fun updateAll() {
            toolbar.update()
            menu.update()
            newMenu.update()
            ActivityTracker.getInstance().inc()
        }
    }
}
