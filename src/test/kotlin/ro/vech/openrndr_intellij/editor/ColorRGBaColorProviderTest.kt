package ro.vech.openrndr_intellij.editor

import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.ui.ColorIcon
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.idea.KotlinFileType
import java.awt.Color

@TestDataPath("\$PROJECT_ROOT/src/testdata")
class ColorRGBaColorProviderTest : LightJavaCodeInsightFixtureTestCase() {

    // TODO: Add dependency on openrndr-color
    override fun getProjectDescriptor(): LightProjectDescriptor {
        return PROJECT_DESCRIPTOR
    }

    override fun getTestDataPath(): String {
        return "./src/testdata"
    }

    private fun assertGutterIconColor(expected: Color, @Language("Kotlin") code: String) {
        myFixture.configureByText(KotlinFileType.INSTANCE, code)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val actual = myFixture.findAllGutters()
        myFixture.doHighlighting()
        assertTrue(actual.size > 1)
        val icon = actual[0].icon as? ColorIcon
        assertEquals(expected, icon?.iconColor)
    }

    fun testColorRGBaRed() {
        assertGutterIconColor(Color.RED, "import org.openrndr.color.* \n fun main() { \n ColorRGBa.RED \n }")
//        assertGutterIconColor(Color.decode("#ffc0cb"), "ColorRGBa.PINK")
    }

    companion object {
        val PROJECT_DESCRIPTOR: DefaultLightProjectDescriptor =
            (JAVA_LATEST as DefaultLightProjectDescriptor).withRepositoryLibrary("org.openrndr:openrndr-color:0.4.0")
    }
}