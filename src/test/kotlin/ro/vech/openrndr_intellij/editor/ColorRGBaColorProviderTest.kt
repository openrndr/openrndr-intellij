package ro.vech.openrndr_intellij.editor

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.ColorIcon
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.idea.KotlinFileType
import java.awt.Color

class ColorRGBaColorProviderTest : BasePlatformTestCase() {

    // TODO: Add dependency on openrndr-color
    override fun getProjectDescriptor(): LightProjectDescriptor? {
        return super.getProjectDescriptor()
    }

    private fun assertGutterIconColor(expected: Color, @Language("Kotlin") code: String) {
        myFixture.configureByText(KotlinFileType.INSTANCE, code)
        val actual = myFixture.findAllGutters()
        UsefulTestCase.assertSize(1, actual)
        val icon = actual[0].icon as? ColorIcon
        assertEquals(expected, icon?.iconColor)
    }

    fun testColorProvider() {
//        assertGutterIconColor(Color.RED, "import org.openrndr.color.* \n fun main() { \n ColorRGBa.RED \n }")
//        assertGutterIconColor(Color.decode("#ffc0cb"), "ColorRGBa.PINK")
    }
}