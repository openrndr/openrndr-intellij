package org.openrndr.plugin.intellij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.awt.Color
import java.io.File

/**
 * Picking a color from the gutter twice, the way the platform does it: the swatch's element is the one the gutter
 * pass saw, and `setColorTo` is called on it in a write action.
 */
class ColorPickerRepeatTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            for ((name, jar) in JARS) PsiTestUtil.addLibrary(model, name, jar.parent, jar.name)
        }
    }.withRepositoryLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")

    private fun pick(color: Color) {
        myFixture.doHighlighting()
        val markers = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project)
            .filter { it.javaClass.name.contains("ColorLineMarkerProvider") }
        val element = (markers.singleOrNull() as LineMarkerInfo<*>?)?.element ?: error("no single color swatch: $markers")
        val provider = ElementColorProvider.EP_NAME.extensionList.first { it is OpenrndrColorProvider }
        WriteAction.run<RuntimeException> { provider.setColorTo(element, color) }
    }

    private fun twice(source: String) {
        if (JARS.size < 3) return println("OPENRNDR 0.5.0 jars not in the Gradle cache; skipped")
        myFixture.configureByText("Sketch.kt", "import org.openrndr.color.*\n\nfun main() {\n    val c = $source\n}\n")
        pick(Color(255, 0, 0))
        val first = myFixture.editor.document.text
        pick(Color(0, 0, 255))
        val second = myFixture.editor.document.text
        assertFalse("second pick did not change the code:\n$second", first == second)
    }

    fun `test rgb twice`() = twice("rgb(0.1, 0.2, 0.3)")
    fun `test constant twice`() = twice("ColorRGBa.PINK")
    fun `test fromHex twice`() = twice("ColorRGBa.fromHex(\"#336699\")")
    fun `test constructor twice`() = twice("ColorRGBa(0.1, 0.2, 0.3)")

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
