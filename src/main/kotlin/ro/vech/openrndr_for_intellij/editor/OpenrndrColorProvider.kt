package ro.vech.openrndr_for_intellij.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.uast.evaluation.uValueOf
import org.jetbrains.uast.getUCallExpression
import org.jetbrains.uast.toUElement
import java.awt.Color

class OpenrndrColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element is LeafPsiElement && element.textMatches("ColorRGBa")) {
            val uElem = element.toUElement() ?: return null
            val exp = uElem.getUCallExpression() ?: return null
            val valueArgs = exp.valueArguments
            val doubles = valueArgs.map {
                (it.uValueOf()?.toConstant()?.value as? Number)?.toFloat() ?: return null
            }
            when (doubles.size) {
                3 -> {
                    val (r, g, b) = doubles
                    return Color(r, g, b)
                }
                4, 5 -> {
                    val (r, g, b, a) = doubles
                    return Color(r, g, b, a)
                }
            }
        }
        return null
    }

    override fun setColorTo(element: PsiElement, color: Color) {

    }
}