package org.openrndr.plugin.intellij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/**
 * Colors the picker cannot write back get a swatch that is not clickable: no platform color marker (which always
 * opens the picker), but a marker of [OpenrndrReadOnlyColorMarkers] that takes the click and does nothing.
 */
class ReadOnlyColorSwatchTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            for ((name, jar) in JARS) PsiTestUtil.addLibrary(model, name, jar.parent, jar.name)
        }
    }.withRepositoryLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    private class Markers(val picker: List<LineMarkerInfo<*>>, val readOnly: List<LineMarkerInfo<*>>)

    private fun markers(source: String): Markers? {
        if (JARS.size < 3) { println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped"); return null }
        myFixture.configureByText("Sketch.kt", "import org.openrndr.color.*\n\nfun main() {\n    val c = $source\n}\n")
        myFixture.doHighlighting()
        val all = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
        return Markers(
            picker = all.filter { it.javaClass.name.contains("ColorLineMarkerProvider") },
            readOnly = all.filterIsInstance<OpenrndrReadOnlyColorMarkers.Swatch>(),
        )
    }

    fun `test LAB swatch is not clickable`() {
        val markers = markers("ColorLABa(50.0, 20.0, -30.0)") ?: return
        assertEmpty(markers.picker)
        assertEquals(1, markers.readOnly.size)
        val renderer = markers.readOnly.single().createGutterRenderer()
        // A click action, so the gutter takes the click instead of toggling a breakpoint; no hand cursor
        assertNotNull(renderer.clickAction)
        assertFalse(renderer.isNavigateAction)
        assertNull(markers.readOnly.single().navigationHandler)
    }

    fun `test LCh swatch is not clickable`() {
        val markers = markers("ColorLCHABa(60.0, 40.0, 200.0)") ?: return
        assertEmpty(markers.picker)
        assertEquals(1, markers.readOnly.size)
    }

    fun `test rgb swatch keeps the picker`() {
        val markers = markers("rgb(0.1, 0.2, 0.3)") ?: return
        assertEquals(1, markers.picker.size)
        assertEmpty(markers.readOnly)
    }

    private companion object {
        val GRADLE = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")

        fun jar(group: String, artifact: String, version: String): File? =
            File(GRADLE, "$group/$artifact/$version").walkTopDown().firstOrNull { it.name == "$artifact-$version.jar" }

        val JARS: List<Pair<String, File>> = listOfNotNull(
            jar("org.openrndr", "openrndr-color-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-color-jvm:0.5.0" to it },
            jar("org.openrndr", "openrndr-math-jvm", "0.5.0")?.let { "Gradle: org.openrndr:openrndr-math-jvm:0.5.0" to it },
            jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0")?.let { "Gradle: kotlinx-serialization-core" to it },
        )
    }
}
