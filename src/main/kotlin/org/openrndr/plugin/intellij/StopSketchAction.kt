package org.openrndr.plugin.intellij

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * The OPENRNDR Mode stop button, next to the play button. It is visible only while something
 * is running, and stops everything that was started from the IDE.
 */
class StopSketchAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && OpenrndrModeAction.isOn && running(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        running(project).forEach { it.destroyProcess() }
    }

    private fun running(project: Project): List<ProcessHandler> =
        ExecutionManager.getInstance(project).getRunningProcesses()
            .filter { !it.isProcessTerminated && !it.isProcessTerminating }

    /** Shows or hides the stop button the moment a run starts or ends, not on the next toolbar refresh. */
    class Refresher : ExecutionListener {
        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = refresh()
        override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) = refresh()

        private fun refresh() = ActivityTracker.getInstance().inc()
    }
}
