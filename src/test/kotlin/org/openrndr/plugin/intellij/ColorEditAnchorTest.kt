package org.openrndr.plugin.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * The gutter swatch stays attached to one PSI leaf (its anchor) while the color picker is open, so the edits
 * that write a picked color back must not replace that leaf.
 */
class ColorEditAnchorTest : BasePlatformTestCase() {

    fun `test replacing a constant selector keeps the receiver anchor`() {
        val file = myFixture.configureByText("A.kt", "val c = ColorRGBa.PINK\n")
        val qualified = PsiTreeUtil.findChildOfType(file, KtDotQualifiedExpression::class.java)!!
        val anchor = PsiTreeUtil.getDeepestFirst(qualified)
        assertEquals("ColorRGBa", anchor.text)

        WriteCommandAction.runWriteCommandAction(project) {
            qualified.selectorExpression!!.replace(KtPsiFactory(project).createExpression("fromHex(\"#ff0000\")"))
        }
        assertEquals("val c = ColorRGBa.fromHex(\"#ff0000\")\n", file.text)
        assertTrue("receiver anchor was replaced", anchor.isValid)
    }

    fun `test replacing an argument list keeps the callee anchor`() {
        val file = myFixture.configureByText("A.kt", "val c = rgb(0.1, 0.2, 0.3)\n")
        val call = PsiTreeUtil.findChildOfType(file, KtCallExpression::class.java)!!
        val anchor = PsiTreeUtil.getDeepestFirst(call)
        assertEquals("rgb", anchor.text)

        WriteCommandAction.runWriteCommandAction(project) {
            call.valueArgumentList!!.replace(KtPsiFactory(project).createCallArguments("(0.5, 0.6, 0.7)"))
        }
        assertEquals("val c = rgb(0.5, 0.6, 0.7)\n", file.text)
        assertTrue("callee anchor was replaced", anchor.isValid)
    }

    fun `test replacing a hex argument keeps the receiver anchor`() {
        val file = myFixture.configureByText("A.kt", "val c = ColorRGBa.fromHex(\"#ff0000\")\n")
        val qualified = PsiTreeUtil.findChildOfType(file, KtDotQualifiedExpression::class.java)!!
        val anchor = PsiTreeUtil.getDeepestFirst(qualified)
        val argument = (qualified.selectorExpression as KtCallExpression).valueArguments.single().getArgumentExpression()!!

        WriteCommandAction.runWriteCommandAction(project) {
            argument.replace(KtPsiFactory(project).createExpression("\"#00ff00\""))
        }
        assertEquals("val c = ColorRGBa.fromHex(\"#00ff00\")\n", file.text)
        assertTrue("receiver anchor was replaced", anchor.isValid)
    }
}
