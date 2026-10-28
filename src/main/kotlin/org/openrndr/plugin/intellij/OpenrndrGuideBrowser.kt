package org.openrndr.plugin.intellij

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.beans.PropertyChangeListener
import java.net.URI
import javax.swing.JComponent

/**
 * The OPENRNDR guide in an editor tab with IntelliJ's built-in browser (JCEF). Links within
 * guide.openrndr.org open in the tab itself; links to other sites open in the system browser.
 * (The platform's own HTML tab sends every clicked link to the system browser.)
 */
object OpenrndrGuideBrowser {
    const val URL = "https://guide.openrndr.org"
    private const val HOST = "guide.openrndr.org"

    /** One guide tab per project, reused when the button is pressed again. */
    private val file = GuideFile()

    /** False when the built-in browser is unavailable; the caller then falls back. */
    fun open(project: Project): Boolean {
        if (!JBCefApp.isSupported()) return false
        OpenFileDescriptor(project, file).navigate(true)
        return true
    }

    fun isGuide(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    class GuideFile : LightVirtualFile("OPENRNDR Guide") {
        override fun isWritable() = false
    }

    class Provider : FileEditorProvider, DumbAware {
        override fun accept(project: Project, file: VirtualFile) = file is GuideFile
        override fun createEditor(project: Project, file: VirtualFile): FileEditor = Editor(file)
        override fun getEditorTypeId() = "openrndr-guide"
        override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }

    private class Editor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
        private val browser = JBCefBrowser.createBuilder().setUrl(URL).build()

        init {
            browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
                ): Boolean {
                    // Embedded content (sub-frames) and guide pages load here; other sites go to the system browser
                    if (!frame.isMain || isGuide(request.url)) return false
                    BrowserUtil.browse(request.url)
                    return true
                }
            }, browser.cefBrowser)

            browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                // Links that would open a new window: guide pages stay in this tab, others go to the system browser
                override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, targetFrameName: String?): Boolean {
                    if (isGuide(targetUrl)) browser.loadURL(targetUrl) else BrowserUtil.browse(targetUrl)
                    return true
                }
            }, browser.cefBrowser)
        }

        override fun getComponent(): JComponent = browser.component
        override fun getPreferredFocusedComponent(): JComponent = browser.component
        override fun getName() = "OPENRNDR Guide"
        override fun getFile() = file
        override fun setState(state: FileEditorState) {}
        override fun isModified() = false
        override fun isValid() = true
        override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
        override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
        override fun dispose() = Disposer.dispose(browser)
    }
}
