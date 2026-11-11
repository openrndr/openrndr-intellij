package org.openrndr.plugin.intellij

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.task.ExecuteRunConfigurationTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner

/**
 * In OPENRNDR Mode, runs Application and Kotlin run configurations the way the play button does
 * ([RunSketchAction]): the gutter run icon, Shift+F10 and the run widget then get the sketch console
 * with clean output, instead of the IDE's full console or Gradle's.
 *
 * Before running a configuration the IDE asks project task runners whether one of them wants to run it
 * instead; that is how Gradle takes over ("delegates") runs, and this one is registered ahead of Gradle.
 * Only Run is taken over; Debug, Coverage and the profilers keep the IDE's own runners.
 */
class SketchProjectTaskRunner : ProjectTaskRunner() {

    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean =
        OpenrndrModeAction.isOn &&
            projectTask is ExecuteRunConfigurationTask &&
            RunSketchAction.sketchOf(projectTask.runProfile) != null

    override fun createExecutionEnvironment(
        project: Project,
        task: ExecuteRunConfigurationTask,
        executor: Executor?,
    ): ExecutionEnvironment? {
        // null passes the run on to the next runner (Gradle, or the IDE's own)
        if (executor?.id != DefaultRunExecutor.EXECUTOR_ID) return null
        val sketch = RunSketchAction.sketchOf(task.runProfile) ?: return null
        return RunSketchAction.launchEnvironment(project, executor, sketch)
    }
}
