package org.openrndr.plugin.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Key

/**
 * Clean console mode (on by default) for sketches started with the play button: their output shows
 * what the sketch prints, without the JVM command line and JVM/library start-up noise, and with
 * the exit line in plain English. Other consoles are left alone.
 *
 * Lines are dropped as they are printed, so toggling it only affects output printed afterwards.
 */
object CleanConsole {
    private const val KEY = "openrndr.cleanConsole"

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value, true)

    /** The `…/bin/java … MainClass` line the IDE prints before the program starts. */
    private val COMMAND_LINE = Regex("""^\S*[/\\]java(\.exe)?\s.*""")

    private val NOISE = listOf(
        // kotlin-logging start-up banner
        Regex("""^kotlin-logging: initializing\b.*"""),
        // JDK warnings about native access and sun.misc.Unsafe, e.g. triggered by LWJGL
        Regex("""^WARNING: (A restricted method in |A terminally deprecated method in |Use --enable-native-access|Restricted methods will be blocked|Please consider reporting this to the maintainers).*"""),
        Regex("""^WARNING: \S+::\S+ (has been called by|will be removed).*"""),
        // Info and warnings from OPENRNDR / LWJGL logged through slf4j-simple; errors stay visible
        Regex("""^\[[^\]]+] (TRACE|DEBUG|INFO|WARN) (org\.openrndr|org\.lwjgl|org\.bytedeco)\..*"""),
        // MoltenVK (Vulkan on macOS) chatter; errors stay visible
        Regex("""^\[mvk-(info|warn|debug)].*"""),
    )

    private val EXIT = Regex("""^Process finished with exit code (-?\d+)(?: \(interrupted by signal (\d+):\s*(\w+)\))?\s*$""")

    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    /**
     * The replacement for [line], `""` to hide it, or `null` to keep it as it is.
     * [isSystem] is true for the IDE's own messages (command line, exit code), false for program output.
     */
    fun clean(line: String, isSystem: Boolean): String? {
        val text = line.trimEnd('\n', '\r').replace(ANSI, "")
        if (isSystem) {
            if (COMMAND_LINE.matches(text)) return ""
            EXIT.matchEntire(text)?.let { return plainExit(it) + line.substring(line.trimEnd('\n', '\r').length) }
        }
        return if (NOISE.any { it.matches(text) }) "" else null
    }

    private fun plainExit(m: MatchResult): String {
        val code = m.groupValues[1].toInt()
        val signal = m.groupValues[2].toIntOrNull()
        return when {
            code == 0 -> "Finished."
            // Stop button, Ctrl+C, or killed: 128 + signal number when the signal is not reported
            signal in setOf(1, 2, 9, 15) || code in setOf(129, 130, 137, 143) -> "Stopped."
            signal != null -> "The program crashed (${m.groupValues[3]})."
            else -> "The program stopped because of an error (exit code $code)."
        }
    }
}

/**
 * Process handler for sketches run with the play button; applies [CleanConsole] to everything it prints.
 * The base class decodes ANSI colors first and hands the text over here, usually a line at a time.
 */
class CleanSketchProcessHandler(commandLine: GeneralCommandLine) : KillableColoredProcessHandler(commandLine) {
    override fun coloredTextAvailable(text: String, attributes: Key<*>) {
        if (!CleanConsole.enabled) return super.coloredTextAvailable(text, attributes)
        val base = (attributes as? ProcessOutputType)?.baseOutputType ?: attributes
        val isSystem = base == ProcessOutputTypes.SYSTEM
        // A chunk may hold several lines
        val cleaned = text.split(Regex("(?<=\n)"))
            .filter { it.isNotEmpty() }
            .joinToString("") { CleanConsole.clean(it, isSystem) ?: it }
        if (cleaned.isNotEmpty()) super.coloredTextAvailable(cleaned, attributes)
    }
}

/** Clean Output toggle on the sketch run's toolbar. */
class ToggleCleanConsoleAction : DumbAwareToggleAction(
    "Clean Output",
    "Hide the JVM command line and start-up noise, and explain how the program ended",
    AllIcons.General.Filter,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = CleanConsole.enabled
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CleanConsole.enabled = state
    }
}
