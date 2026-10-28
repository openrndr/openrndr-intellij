package org.openrndr.plugin.intellij

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.ExecutionException
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.task.ProjectTaskManager
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.Icon

/**
 * The OPENRNDR Mode play button: runs the sketch in the file open in the editor, and nothing else.
 *
 * It runs through IntelliJ's own Java runner, not Gradle. Gradle only takes over
 * ("delegates") run configurations, so this launches a plain [ModuleRunProfile]
 * instead of running an Application or Kotlin run configuration.
 */
class RunSketchAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && OpenrndrModeAction.isOn
        e.presentation.isEnabled = project != null && selectedFile(project)?.let { findSketch(project, it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = selectedFile(project) ?: return
        run(project, file)
    }

    private fun selectedFile(project: Project): VirtualFile? = FileEditorManager.getInstance(project).selectedEditor?.file

    private data class Sketch(
        val module: Module,
        val mainClass: String,
        val name: String,
        val vmParameters: String?,
        val programParameters: String?,
    )

    companion object {
        private val SKETCH_KEY = Key.create<Pair<Long, Sketch?>>("openrndr.sketch")

        /**
         * Compiles and runs the program in [file], or calls [onNotRunnable] when the IDE cannot run that file
         * (see [findSketch]). Finding the main class reads the indexes, too slow for the UI thread, so it happens
         * in the background once indexing is done.
         */
        fun run(project: Project, file: VirtualFile, onNotRunnable: () -> Unit = {}) {
            ReadAction.nonBlocking<Sketch?> { findSketch(project, file) }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.nonModal()) { sketch ->
                    if (sketch == null) onNotRunnable() else buildAndLaunch(project, sketch)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun buildAndLaunch(project: Project, sketch: Sketch) {
            FileDocumentManager.getInstance().saveAllDocuments()
            // Compile first (the project's build setting decides how), then launch with IntelliJ's runner
            ProjectTaskManager.getInstance(project).build(sketch.module).onSuccess { result ->
                if (result.isAborted || result.hasErrors()) return@onSuccess
                ApplicationManager.getApplication().invokeLater({ launch(project, sketch) }, project.disposed)
            }
        }

        /** The main class [run] starts for [file]; null when the IDE cannot run that file. */
        internal fun mainClass(project: Project, file: VirtualFile): String? = findSketch(project, file)?.mainClass

        private fun launch(project: Project, sketch: Sketch) {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            try {
                ExecutionEnvironmentBuilder.create(project, executor, SketchRunProfile(sketch)).buildAndExecute()
            } catch (ex: ExecutionException) {
                ExecutionUtil.handleExecutionError(project, executor.toolWindowId, sketch.name, ex)
            }
        }

        /**
         * The main class of [file], if the IDE can run that file.
         * IntelliJ's own run configuration producers decide, so a Java `main`, a Kotlin `fun main()`
         * and anything else that gets a gutter run icon all count. The configuration they produce
         * is only read, never saved or run.
         */
        private fun findSketch(project: Project, file: VirtualFile): Sketch? {
            // The run configuration producers need the indexes; until indexing is done the button stays disabled
            if (DumbService.isDumb(project)) return null
            val psi = PsiManager.getInstance(project).findFile(file) as? PsiClassOwner ?: return null
            val module = ModuleUtilCore.findModuleForFile(file, project) ?: return null

            // Producers are not cheap and update() runs often, so cache per file version
            psi.getUserData(SKETCH_KEY)?.let { (stamp, sketch) -> if (stamp == psi.modificationStamp) return sketch }

            val candidates = listOf(psi) + psi.classes
            val params = candidates.firstNotNullOfOrNull { element ->
                ConfigurationContext.createEmptyContextForLocation(PsiLocation(element))
                    .configurationsFromContext.orEmpty()
                    .firstNotNullOfOrNull { it.configuration as? CommonJavaRunConfigurationParameters }
                    ?.takeIf { !it.runClass.isNullOrBlank() }
            }
            val sketch = params?.let {
                Sketch(module, it.runClass!!, file.nameWithoutExtension, it.vmParameters, it.programParameters)
            }
            psi.putUserData(SKETCH_KEY, psi.modificationStamp to sketch)
            return sketch
        }
    }

    private class SketchRunProfile(private val sketch: Sketch) : ModuleRunProfile {
        override fun getName() = sketch.name
        override fun getIcon(): Icon = AllIcons.Actions.Execute
        override fun getModules(): Array<Module> = arrayOf(sketch.module)

        override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
            object : JavaCommandLineState(environment) {
                override fun createJavaParameters() = JavaParameters().apply {
                    configureByModule(sketch.module, JavaParameters.JDK_AND_CLASSES_AND_TESTS)
                    mainClass = sketch.mainClass
                    vmParametersList.addParametersString(sketch.vmParameters)
                    programParametersList.addParametersString(sketch.programParameters)
                    workingDirectory = environment.project.basePath
                }

                // Clean console mode lives in the process handler, so it only touches sketches run from here
                override fun startProcess(): OSProcessHandler =
                    CleanSketchProcessHandler(createCommandLine()).also { ProcessTerminatedListener.attach(it) }

                // No side toolbar on the console; its useful actions join the run's top toolbar
                override fun createConsole(executor: Executor): ConsoleView =
                    SketchConsole(environment.project, GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(sketch.module, true))

                override fun createActions(console: ConsoleView?, handler: ProcessHandler, executor: Executor): Array<AnAction> =
                    super.createActions(console, handler, executor) +
                        listOfNotNull((console as? SketchConsole)?.topActions, ToggleCleanConsoleAction())
            }
    }
}
