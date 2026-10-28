package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAwareAction

/**
 * Opens the OPENRNDR guide in an editor tab. With the Web Browser (JCEF) plugin, [OpenrndrGuideBrowser]
 * keeps guide links in the tab; otherwise the platform's HTML tab is used.
 */
class OpenrndrGuideAction : DumbAwareAction(AllIcons.Actions.Help) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (jcefInstalled() && OpenrndrGuideBrowser.open(project)) return
        HTMLEditorProvider.openEditor(project, "OPENRNDR Guide", OpenrndrGuideBrowser.URL, FALLBACK_HTML)
    }

    /** [OpenrndrGuideBrowser] needs the optional Web Browser (JCEF) plugin. */
    private fun jcefInstalled(): Boolean {
        val id = PluginId.getId("com.intellij.modules.jcef")
        return PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id)
    }

    private companion object {
        /** Shown when the page does not load in the built-in browser. */
        val FALLBACK_HTML =
            "<html><body><p>The OPENRNDR guide did not load.</p>" +
                "<p><a href=\"${OpenrndrGuideBrowser.URL}\">Open ${OpenrndrGuideBrowser.URL} in your browser</a></p></body></html>"
    }
}
