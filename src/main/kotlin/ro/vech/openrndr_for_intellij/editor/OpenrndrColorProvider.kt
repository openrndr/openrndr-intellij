package ro.vech.openrndr_for_intellij.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.psi.*
import java.awt.Color

class OpenrndrColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element is LeafPsiElement && element.textMatches("ColorRGBa")) {
            val sibling = element.context?.nextSibling
            if (sibling is KtValueArgumentList) {
                val args = sibling.arguments
                val floats = args.take(4).map { it.getArgumentExpression()?.text?.toFloatOrNull() ?: return null }
                when (args.size) {
                    3 -> {
                        val (r, g, b) = floats
                        return Color(r, g, b)
                    }
                    4, 5 -> {
                        val (r, g, b, a) = floats
                        return Color(r, g, b, a)
                    }
                }
            }
        }
        return null
    }

    override fun setColorTo(element: PsiElement, color: Color) {

    }
}