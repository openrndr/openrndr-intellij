package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.MavenDependencyUtil
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.idea.KotlinFileType
import java.awt.Color

class ColorRGBaColorProviderTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor {
        return PROJECT_DESCRIPTOR
    }

    private fun assertGutterIconColor(expected: Color, @Language("Kotlin") code: String) {
        myFixture.configureByText(KotlinFileType.INSTANCE, code)
        println(myFixture.doHighlighting().joinToString("\n"))
        val actual = myFixture.findAllGutters()
        assertTrue(actual.size > 1)
//        val icon = actual[1].icon as? ColorIcon
//        assertEquals(expected, icon?.iconColor)
    }

    fun testColorRGBaRed() {
        assertGutterIconColor(Color.RED, "import org.openrndr.color.ColorRGBa\n\nfun main() {\n    ColorRGBa.RED\n}")
    }

    companion object {
//        val PROJECT_DESCRIPTOR: DefaultLightProjectDescriptor =
//            (JAVA_LATEST as DefaultLightProjectDescriptor).withRepositoryLibrary("org.openrndr:openrndr-color:0.4.0")
//        val PROJECT_DESCRIPTOR = DefaultLightProjectDescriptor(Supplier { IdeaTestUtil.getMockJdk17() }, listOf("org.openrndr:openrndr-color:0.4.0"))

        private val PROJECT_DESCRIPTOR = object : ProjectDescriptor(LanguageLevel.JDK_11) {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                MavenDependencyUtil.addFromMaven(model, "org.openrndr:openrndr-color:0.4.0")
            }
        }
    }
}
