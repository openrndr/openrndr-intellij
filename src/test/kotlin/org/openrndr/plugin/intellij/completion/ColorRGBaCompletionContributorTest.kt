package org.openrndr.plugin.intellij.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.TestLookupElementPresentation
import org.openrndr.plugin.intellij.ColorRGBaTestCase
import org.openrndr.plugin.intellij.ui.RoundColorIcon
import org.openrndr.plugin.intellij.utils.ColorUtil.staticColorMap

@TestDataPath("\$PROJECT_ROOT/testData/completion")
class ColorRGBaCompletionContributorTest : ColorRGBaTestCase() {
    override fun getTestDataPath(): String = super.getTestDataPath() + "/completion"

    override fun setUp() {
        super.setUp()
        myFixture.configureByFile(getTestName(true) + ".kt")
    }

    fun testStaticColorRGBaCompletion() {
        val staticColorPairs = myFixture.completeBasic().filter { it.lookupString in staticColorMap }
            .map { it.lookupString to (LookupElementPresentation.renderElement(it).icon as RoundColorIcon).color }
        assertNotEmpty(staticColorPairs)
        staticColorPairs.forEach { (name, color) ->
            assertEquals(staticColorMap[name], color)
        }
    }

    fun testExpensiveColorRGBaCompletion() {
        val colorIcons = myFixture.completeBasic().filter { it.lookupString in "myVar0".."myVar3" }
            .map(TestLookupElementPresentation::renderReal)
            .map { it.typeIcon as RoundColorIcon }
        assertSize(4, colorIcons)
    }
}