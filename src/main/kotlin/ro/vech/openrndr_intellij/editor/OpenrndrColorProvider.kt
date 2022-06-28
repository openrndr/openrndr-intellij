package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.mapArgumentsToParameters
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.ValueArgument
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.evaluation.uValueOf
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.values.*
import java.awt.Color

class OpenrndrColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        val parent = (element.parent as? KtNameReferenceExpression) ?: return null
        if (parent.parent !is KtCallExpression) return null

        val resolvedCall = parent.resolveToCall() ?: return null
        val descriptor = resolvedCall.resultingDescriptor
        if (descriptor.containingPackage()?.asString() != "org.openrndr.color") return null

        val bindingContext = parent.analyze()
        val call = parent.getCall(bindingContext) ?: return null
        val argToParamMap = call.mapArgumentsToParameters(descriptor)
        val args = argToParamMap.getArgumentsInCanonicalOrder() ?: return null

        return when (descriptor.name.asString()) {
            "rgb" -> {
                when (val firstArg = args.firstOrNull()) {
                    is UFloatConstant -> {
                        val floats = args.toFloats() ?: return null
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
            "rgba" -> {
                if (args.size != 4) return null
                val floats = args.toFloats() ?: return null
                Color(floats[0], floats[1], floats[2], floats[3])
            }
            "fromHex" -> {
                when (val hex = args.firstOrNull()) {
                    is UIntConstant -> fromHex(hex.value)
                    is UStringConstant -> fromHex(hex.value)
                    else -> null
                }
            }
            "hsv" -> {
                if (args.size != 3) return null
                val floats = args.toFloats() ?: return null
                Color.getHSBColor(floats[0] / 360.0f, floats[1], floats[2])
            }
            "hsva" -> {
                if (args.size != 4) return null
                val floats = args.toFloats() ?: return null
                val alpha = (floats[3] * 255.0f).toInt()
                val rgb = Color.HSBtoRGB(floats[0] / 360.0f, floats[1], floats[2])
                Color(rgbWithAlpha(rgb, alpha), true)
            }
            "<init>" -> {
                val className = descriptor.containingDeclaration.name.asString()
                val floats = args.take(4).toFloats() ?: return null
                when (className) {
                    "ColorRGBa" -> when (args.size) {
                        3 -> Color(floats[0], floats[1], floats[2])
                        4, 5 -> Color(floats[0], floats[1], floats[2], floats[3])
                        else -> null
                    }
                    "ColorHSVa" -> when (args.size) {
                        3 -> Color.getHSBColor(floats[0] / 360.0f, floats[1], floats[2])
                        4, 5 -> {
                            val alpha = (floats[3] * 255.0f).toInt()
                            val rgb = Color.HSBtoRGB(floats[0] / 360.0f, floats[1], floats[2])
                            Color(rgbWithAlpha(rgb, alpha), true)
                        }
                        else -> null
                    }
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

        fun Map<ValueArgument, ValueParameterDescriptor>.getArgumentsInCanonicalOrder(): Collection<UConstant>? {
            return toList().sortedBy { it.second.index }.map {
                val uExpression = it.first.getArgumentExpression()?.toUElement() as? UExpression ?: return null
                uExpression.uValueOf()?.toConstant() ?: return null
            }
        }

        fun Iterable<UConstant>.toFloats(): List<Float>? {
            return map {
                (it.value as? Number)?.toFloat() ?: return null
            }
        }

        fun rgbWithAlpha(rgb: Int, alpha: Int) = (alpha and 0xff shl 24) xor (rgb shl 8 ushr 8)
    }
}
