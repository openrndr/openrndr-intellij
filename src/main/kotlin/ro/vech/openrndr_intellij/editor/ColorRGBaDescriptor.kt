package ro.vech.openrndr_intellij.editor

import com.intellij.ui.ColorHexUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import org.openrndr.math.CastableToVector4
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toAWTColor
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.NumberFormat

internal enum class ColorRGBaDescriptor {
    FromHex {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun argumentsFromColor(color: Color): Array<String> {
            val hex = (color.rgb and 0xffffff).toString(16)
            return arrayOf("0x$hex")
        }

        override fun colorFromArguments(arguments: List<Any>): Color? {
            return when (val hex = arguments.firstOrNull()) {
                is Int -> ColorRGBa.fromHex(hex).toAWTColor()
                is String -> ColorHexUtil.fromHexOrNull(hex)
                else -> null
            }
        }
    },
    RGB {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun argumentsFromColor(color: Color): Array<String> {
            return doubleArrayOf(
                color.red / 255.0, color.green / 255.0, color.blue / 255.0, color.alpha / 255.0
            ).formatNumbers()
        }

        override fun colorFromArguments(arguments: List<Any>): Color? {
            return when (val firstArg = arguments.firstOrNull()) {
                is Double -> {
                    val doubles = arguments.toDoubles() ?: return null
                    // TODO: Handle default parameters and alpha
                    when (doubles.size) {
                        1 -> rgb(doubles[0])
                        2 -> rgb(doubles[0], doubles[1])
                        3 -> rgb(doubles[0], doubles[1], doubles[2])
                        4 -> rgb(doubles[0], doubles[1], doubles[2], doubles[3])
                        else -> null
                    }?.toAWTColor()
                }
                is String -> ColorHexUtil.fromHexOrNull(firstArg)
                else -> null
            }
        }
    },
    HSV {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(arguments: List<Any>): Color? {
            val doubles = arguments.toDoubles() ?: return null
            return when (doubles.size) {
                3 -> hsv(doubles[0], doubles[1], doubles[2])
                4 -> hsv(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    },
    ColorRGBaConstructor {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun argumentsFromColor(color: Color): Array<String> {
            return doubleArrayOf(
                color.red / 255.0, color.green / 255.0, color.blue / 255.0, color.alpha / 255.0
            ).formatNumbers()
        }

        override fun colorFromArguments(arguments: List<Any>): Color? {
            val doubles = arguments.toDoubles() ?: return null
            return when (doubles.size) {
                3 -> ColorRGBa(doubles[0], doubles[1], doubles[2])
                // TODO: Handle 4 args with linearity instead of alpha?
                4, 5 -> ColorRGBa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    },
    ColorHSVaConstructor {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(arguments: List<Any>): Color? {
            val doubles = arguments.toDoubles() ?: return null
            return when (doubles.size) {
                3 -> ColorHSVa(doubles[0], doubles[1], doubles[2])
                // TODO: Handle 4 args with linearity instead of alpha?
                4, 5 -> ColorHSVa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    };

    abstract val conversionFunction: (ColorRGBa) -> CastableToVector4
    open fun argumentsFromColor(color: Color): Array<String> {
        val colorVector = conversionFunction.invoke(color.toColorRGBa()).toVector4()
        // If alpha is fully opaque, we can skip it
        return if (colorVector.w == 1.0) {
            colorVector.xyz.toDoubleArray().formatNumbers()
        } else {
            colorVector.toDoubleArray().formatNumbers()
        }
    }

    abstract fun colorFromArguments(arguments: List<Any>): Color?

    companion object {
        fun fromCallableDescriptor(targetDescriptor: CallableDescriptor): ColorRGBaDescriptor? {
            return when (targetDescriptor.getImportableDescriptor().name.asString()) {
                "fromHex" -> FromHex
                "rgb" -> RGB
                "hsv" -> HSV
                "ColorRGBa" -> ColorRGBaConstructor
                "ColorHSVa" -> ColorHSVaConstructor
                else -> null
            }
        }

        fun Iterable<Any>.toDoubles(): List<Double>? {
            return map { it as? Double ?: return null }
        }

        private val numberFormatter: NumberFormat = DecimalFormat.getNumberInstance().also {
            it.minimumFractionDigits = 1
            it.maximumFractionDigits = 3
        }

        fun DoubleArray.formatNumbers() = Array<String>(size) {
            numberFormatter.format(this[it])
        }
    }
}
