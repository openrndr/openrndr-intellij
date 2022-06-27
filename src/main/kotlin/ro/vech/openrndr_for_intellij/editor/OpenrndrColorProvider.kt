package ro.vech.openrndr_for_intellij.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.uast.evaluation.uValueOf
import org.jetbrains.uast.getUCallExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.util.isConstructorCall
import org.jetbrains.uast.values.UFloatConstant
import org.jetbrains.uast.values.UIntConstant
import org.jetbrains.uast.values.UStringConstant
import java.awt.Color

class OpenrndrColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        if (element.parent?.parent !is KtCallExpression) return null
        val uCallExpression = element.toUElement()?.getUCallExpression() ?: return null
        if (uCallExpression.getExpressionType()?.canonicalText != "org.openrndr.color.ColorRGBa") return null

        val valueArgs = uCallExpression.valueArguments
        return when {
            uCallExpression.methodName == "rgb" -> {
                when (val firstArg = valueArgs.firstOrNull()?.uValueOf()) {
                    is UFloatConstant -> {
                        val floats = valueArgs.map {
                            (it.uValueOf()?.toConstant()?.value as? Number)?.toFloat() ?: return null
                        }
                        when (floats.size) {
                            1 -> Color(floats[0], floats[0], floats[0])
                            2 -> Color(floats[0], floats[1], floats[0])
                            3 -> Color(floats[0], floats[1], floats[2])
                            else -> null
                        }
                    }
                    is UStringConstant -> fromHex(firstArg.value)
                    else -> null
                }
            }
            uCallExpression.methodName == "rgba" && valueArgs.size == 4 -> {
                val floats = valueArgs.map {
                    (it.uValueOf()?.toConstant()?.value as? Number)?.toFloat() ?: return null
                }
                Color(floats[0], floats[1], floats[2], floats[3])
            }
            uCallExpression.methodName == "fromHex" -> {
                when (val hex = valueArgs.firstOrNull()?.uValueOf()) {
                    is UIntConstant -> fromHex(hex.value)
                    is UStringConstant -> fromHex(hex.value)
                    else -> null
                }
            }
            uCallExpression.isConstructorCall() -> {
                val floats = valueArgs.map {
                    (it.uValueOf()?.toConstant()?.value as? Number)?.toFloat() ?: return null
                }
                when (floats.size) {
                    3 -> Color(floats[0], floats[1], floats[2])
                    4, 5 -> Color(floats[0], floats[1], floats[2], floats[3])
                    else -> null
                }
            }
            else -> null
        }
    }

    override fun setColorTo(element: PsiElement, color: Color) {
    }

    private companion object {
        fun fromHex(hex: Int): Color = Color(hex)

        fun fromHex(hex: String): Color? {
            val hexNormalized = when (hex.length) {
                4 -> String(charArrayOf('#', hex[1], hex[1], hex[2], hex[2], hex[3], hex[3]))
                7 -> hex
                else -> return null
            }
            return try {
                Color.decode(hexNormalized)
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}