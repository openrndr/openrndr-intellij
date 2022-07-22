package ro.vech.openrndr_intellij.editor

import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.util.ui.ColorIcon
import org.jetbrains.kotlin.idea.KotlinFileType
import java.awt.Color

class ColorRGBaColorProviderTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = PROJECT_DESCRIPTOR

    private fun assertGutterIconColor(expected: Color, colorRGBaColor: String) {
        val code = standardColorRGBaExpressionTemplate(colorRGBaColor)
        myFixture.configureByText(KotlinFileType.INSTANCE, code)
        val gutterMarks = myFixture.findAllGutters()
        assertTrue(gutterMarks.size == 1)
        val actualIcon = gutterMarks[0].icon as? ColorIcon
        assertEquals(expected, actualIcon?.iconColor)
    }

    fun testColorRGBaStatic() {
        assertGutterIconColor(Color.RED, "ColorRGBa.RED")
        assertGutterIconColor(Color.BLUE, "ColorRGBa.BLUE")
    }

    companion object {
        val PROJECT_DESCRIPTOR = DefaultLightProjectDescriptor(
            { IdeaTestUtil.getMockJdk17() },
            listOf("org.openrndr:openrndr-color:0.4.0", "org.openrndr.extra:orx-color:0.4.0-1")
        )

        fun standardColorRGBaExpressionTemplate(expression: String): String {
            return "import org.openrndr.color.ColorRGBa\n\nfun main() { $expression }"
        }
    }
}
