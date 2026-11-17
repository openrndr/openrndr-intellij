package org.openrndr.plugin.intellij

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.LightVirtualFile

/**
 * The Rename button in OPENRNDR Mode: the symbol under the caret when it is in the project, else the file being
 * edited, with a tooltip that says which.
 */
class SketchRenameTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor =
        DefaultLightProjectDescriptor().withRepositoryLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    private val action by lazy { SketchRename(ActionManager.getInstance().getAction("RenameElement")) }

    private fun configure(caretAt: String) {
        val text = "fun main() {\n    val radius = 1.0\n    println(radius)\n    \n}\n"
        myFixture.configureByText("Spirals.kt", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf(caretAt))
    }

    /** What the main toolbar sees with the editor focused: the editor's data, as its component provides it. */
    private fun editorContext(): DataContext {
        val element = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted)
        return SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(CommonDataKeys.PSI_ELEMENT, element)
            .add(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR, FileEditorManager.getInstance(project).selectedEditor)
            .build()
    }

    private fun tooltip(context: DataContext): String? {
        val e = TestActionEvent.createTestEvent(action, context)
        action.update(e)
        return e.presentation.text.takeIf { e.presentation.isEnabled }
    }

    fun `test caret on a variable renames the variable`() {
        configure(caretAt = "radius)")
        // The kind ("Variable", "Property", "Function", ...) comes from the Kotlin plugin
        assertEquals("Rename Variable 'radius'", tooltip(editorContext()))
    }

    fun `test caret on a library call is left to the platform`() {
        configure(caretAt = "println")
        // Enabled and named; on click the platform says a library function cannot be renamed
        assertEquals("Rename Function 'println'", tooltip(editorContext()))
    }

    fun `test caret on whitespace renames the file`() {
        configure(caretAt = "    \n}")
        assertEquals("Rename File 'Spirals.kt'", tooltip(editorContext()))
    }

    fun `test caret on a bracket renames the file`() {
        configure(caretAt = "{\n")
        assertEquals("Rename File 'Spirals.kt'", tooltip(editorContext()))
    }

    fun `test console focused renames the file in the selected tab`() {
        configure(caretAt = "radius")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR, FileEditorManager.getInstance(project).selectedEditor)
            .build()
        assertEquals("Rename File 'Spirals.kt'", tooltip(context))
    }

    fun `test nothing to rename is grayed out`() {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        assertNull(tooltip(context))
    }

    fun `test a file outside the project is not renamed`() {
        // Like the guide tab: an editor on a file that is not in the project
        val guide = LightVirtualFile("guide.html", "<html/>")
        assertNull(SketchRename.fileToRename(project, focused = null, lastEdited = guide))
    }
}
