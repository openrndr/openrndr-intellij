package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.mapArgumentsToParameters
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.constants.IntegerValueConstant
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.TypeUtils
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ConvertibleToColorRGBa
import java.awt.Color
import kotlin.reflect.full.memberProperties

class ColorRGBaColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        val parent = (element.parent as? KtNameReferenceExpression) ?: return null
        if (parent.parent !is KtCallExpression && parent.parent !is KtDotQualifiedExpression) return null

        val resolvedCall = parent.resolveToCall() ?: return null
        val descriptor = resolvedCall.resultingDescriptor
        val packageName = descriptor.containingPackage()?.asString()
        if (packageName != "org.openrndr.color" && packageName != "org.openrndr.extras.color.presets") return null

        val bindingContext = parent.analyze()
        val call = parent.getCall(bindingContext) ?: return null
        val argToParamMap = call.mapArgumentsToParameters(descriptor)
        val args = argToParamMap.getArgumentsInCanonicalOrder(bindingContext) ?: return null

        return when (val descriptorName = descriptor.name.asString()) {
            "rgb" -> {
                when (val firstArg = args.firstOrNull()) {
                    is Double -> {
                        val floats = args.toFloats() ?: return null
                        when (floats.size) {
                            1 -> Color(floats[0], floats[0], floats[0])
                            2 -> Color(floats[0], floats[1], floats[0])
                            3 -> Color(floats[0], floats[1], floats[2])
                            else -> null
                        }
                    }
                    is String -> fromHex(firstArg)
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
                    is Int -> fromHex(hex)
                    is String -> fromHex(hex)
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
            else -> staticColorMap[descriptorName]
        }
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val command = {}
        // TODO: Should use message bundle for command name
        CommandProcessor.getInstance()
            .executeCommand(element.project, command, "Change Color", null, document)
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

        /**
         * Also resolves arguments to constant values.
         */
        fun Map<ValueArgument, ValueParameterDescriptor>.getArgumentsInCanonicalOrder(bindingContext: BindingContext): Collection<Any>? {
            return toList().sortedBy { it.second.index }.map {
                val expression = it.first.getArgumentExpression() ?: return null
                when (val constant = ConstantExpressionEvaluator.getConstant(expression, bindingContext)) {
                    is IntegerValueTypeConstant -> (constant.toConstantValue(TypeUtils.DONT_CARE) as? IntegerValueConstant)?.value
                    is TypedCompileTimeConstant -> constant.constantValue.value
                    else -> null
                } ?: return null
            }
        }

        /**
         * Assumes you have `Iterable<Number>`.
         */
        fun Iterable<Any>.toFloats(): List<Float>? {
            return map {
                (it as? Number)?.toFloat() ?: return null
            }
        }

        fun rgbWithAlpha(rgb: Int, alpha: Int) = (alpha and 0xff shl 24) xor (rgb shl 8 ushr 8)

        fun ConvertibleToColorRGBa.toAWTColor(): Color {
            val (r, g, b, a) = this as? ColorRGBa ?: toRGBa()
            return Color(
                (r * 255).toInt(),
                (g * 255).toInt(),
                (b * 255).toInt(),
                (a * 255).toInt()
            )
        }

        fun Color.toColorRGBa(): ColorRGBa {
            return ColorRGBa(
                red / 255.0,
                green / 255.0,
                blue / 255.0,
                alpha / 255.0
            )
        }

        val staticColorMap: Map<String, Color> = let {
            val map = mutableMapOf<String, ColorRGBa>()
            for (property in ColorRGBa.Companion::class.memberProperties) {
                map[property.name] = property.getter.call(ColorRGBa.Companion) as ColorRGBa
            }
            val extensionColorsJavaClass = Class.forName("org.openrndr.extras.color.presets.ColorsKt")
            for (method in extensionColorsJavaClass.declaredMethods) {
                // Every generated java method is prefixed with "get"
                map[method.name.drop(3)] = method.invoke(ColorRGBa::javaClass, ColorRGBa.Companion) as ColorRGBa
            }
            map.mapValues { it.value.toAWTColor() }
        }
    }
}
