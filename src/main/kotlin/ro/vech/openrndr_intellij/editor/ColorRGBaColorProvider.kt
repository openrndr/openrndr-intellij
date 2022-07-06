package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.containers.map2Array
import org.jetbrains.kotlin.descriptors.CallableDescriptor
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
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.jetbrains.kotlin.types.TypeUtils
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ConvertibleToColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.NumberFormat
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

        return when (val descriptorName = descriptor.getImportableDescriptor().name.asString()) {
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
            "ColorRGBa" -> {
                val floats = args.take(4).toFloats() ?: return null
                when (args.size) {
                    3 -> Color(floats[0], floats[1], floats[2])
                    4, 5 -> Color(floats[0], floats[1], floats[2], floats[3])
                    else -> null
                }
            }
            "ColorHSVa" -> {
                val floats = args.take(4).toFloats() ?: return null
                when (args.size) {
                    3 -> Color.getHSBColor(floats[0] / 360.0f, floats[1], floats[2])
                    4, 5 -> {
                        val alpha = (floats[3] * 255.0f).toInt()
                        val rgb = Color.HSBtoRGB(floats[0] / 360.0f, floats[1], floats[2])
                        Color(rgbWithAlpha(rgb, alpha), true)
                    }
                    else -> null
                }
            }
            else -> staticColorMap[descriptorName]
        }
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val parent = (element.context as? KtNameReferenceExpression) ?: return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val command = Runnable {
            val resolvedCall = parent.resolveToCall() ?: return@Runnable
            val descriptor = resolvedCall.resultingDescriptor
            val colorRGBaDescriptor = ColorRGBaDescriptor.getDescriptorType(descriptor) ?: return@Runnable
            val psiFactory = KtPsiFactory(element)
            val newExpression = psiFactory.createValueArgumentListByPattern(
                colorRGBaDescriptor.expressionPattern,
                *colorRGBaDescriptor.expressionArguments(color)
            )
            parent.nextSibling.replace(newExpression)
        }
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

        enum class ColorRGBaDescriptor {
            FromHex {
                override val expressionPattern: String = "($0)"
                override fun expressionArguments(color: Color): Array<String> {
                    val hex = (color.rgb and 0xffffff).toString(16)
                    return arrayOf("0x$hex")
                }
            },
            RGB {
                override val expressionPattern: String = "($0, $1, $2)"
                override fun expressionArguments(color: Color): Array<String> {
                    return arrayOf(color.red / 255.0, color.blue / 255.0, color.green / 255.0).map2Array(numberFormatter::format)
                }
            },
            RGBA {
                override val expressionPattern: String = "($0, $1, $2, $3)"
                override fun expressionArguments(color: Color): Array<String> {
                    return arrayOf(color.red / 255.0, color.blue / 255.0, color.green / 255.0, color.alpha / 255.0).map2Array(numberFormatter::format)
                }
            },
            HSV {
                override val expressionPattern: String = "($0, $1, $2)"
                override fun expressionArguments(color: Color): Array<String> {
                    val hsvColor = color.toColorRGBa().toHSVa()
                    return arrayOf(hsvColor.h, hsvColor.s, hsvColor.v).map2Array(numberFormatter::format)
                }
            },
            HSVA {
                override val expressionPattern: String = "($0, $1, $2, $3)"
                override fun expressionArguments(color: Color): Array<String> {
                    val hsvColor = color.toColorRGBa().toHSVa()
                    return arrayOf(hsvColor.h, hsvColor.s, hsvColor.v, hsvColor.a).map2Array(numberFormatter::format)
                }
            },
            ColorRGBa {
                override val expressionPattern: String = "($0, $1, $2, $3)"
                override fun expressionArguments(color: Color): Array<String> {
                    return arrayOf(color.red / 255.0, color.blue / 255.0, color.green / 255.0, color.alpha / 255.0).map2Array(numberFormatter::format)
                }
            },
            ColorHSVa {
                override val expressionPattern: String = "($0, $1, $2, $3)"
                override fun expressionArguments(color: Color): Array<String> {
                    val hsvColor = color.toColorRGBa().toHSVa()
                    return arrayOf(hsvColor.h, hsvColor.s, hsvColor.v, hsvColor.a).map2Array(numberFormatter::format)
                }
            };

            abstract val expressionPattern: String
            abstract fun expressionArguments(color: Color): Array<String>

            companion object {
                fun getDescriptorType(targetDescriptor: CallableDescriptor): ColorRGBaDescriptor? {
                    return when (targetDescriptor.getImportableDescriptor().name.asString()) {
                        "fromHex" -> FromHex
                        "rgb" -> RGB
                        "rgba" -> RGBA
                        "hsv" -> HSV
                        "hsva" -> HSVA
                        "ColorRGBa" -> ColorRGBa
                        "ColorHSVa" -> ColorHSVa
                        else -> null
                    }
                }

                val numberFormatter: NumberFormat = DecimalFormat.getNumberInstance().also {
                    it.maximumFractionDigits = 3
                }
            }
        }
    }
}
