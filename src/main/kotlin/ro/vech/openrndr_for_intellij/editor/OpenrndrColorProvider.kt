package ro.vech.openrndr_for_intellij.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.uast.evaluation.uValueOf
import org.jetbrains.uast.getUCallExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.values.UIntConstant
import org.jetbrains.uast.values.UStringConstant
import java.awt.Color

class OpenrndrColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null

        val uElement = element.toUElement() ?: return null
        val uCallExpression = uElement.getUCallExpression() ?: return null

        if (uCallExpression.getExpressionType()?.canonicalText != "org.openrndr.color.ColorRGBa") return null

        val valueArgs = uCallExpression.valueArguments
        return if (uCallExpression.methodIdentifier?.name == "fromHex") {
            when (val hex = valueArgs.firstOrNull()?.uValueOf()) {
                is UStringConstant -> fromHex(hex.value)
                is UIntConstant -> fromHex(hex.value)
                else -> null
            }
        } else {
            val floats = valueArgs.map {
                (it.uValueOf()?.toConstant()?.value as? Number)?.toFloat() ?: return null
            }
            when (floats.size) {
                3 -> {
                    val (r, g, b) = floats
                    Color(r, g, b)
                }
                4, 5 -> {
                    val (r, g, b, a) = floats
                    Color(r, g, b, a)
                }
                else -> null
            }
        }
    }

    override fun setColorTo(element: PsiElement, color: Color) {
    }

    private companion object {
        fun fromHex(hex: Int): Color {
            return Color(hex shr 16 and 0xff, hex shr 8 and 0xff, hex and 0xff)
        }

        fun fromHex(hex: String): Color {
            return Color.decode(hex)
        }
    }
}