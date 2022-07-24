package ro.vech.openrndr_intellij.editor

import com.jetbrains.rd.util.firstOrNull
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.jetbrains.kotlin.resolve.constants.IntValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import org.openrndr.extra.color.spaces.*
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toAWTColor
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.NumberFormat

internal typealias ArgumentMap = Map<ValueParameterDescriptor, ConstantValueContainer<*>>

internal sealed class ColorRGBaDescriptor {
    object FromHex : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color): Array<String> {
            val hex = color.rgb.let {
                // If alpha is 0xff, we won't need it
                if (it and -0x1000000 == -0x1000000) {
                    (it and 0xffffff).toString(16)
                } else {
                    // We need to rotate it from AARRGGBB to RRGGBBAA
                    it.rotateLeft(8).toUInt().toString(16)
                }
            }
            return arrayOf("\"#$hex\"")
        }

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            return when (val firstValue =
                (argumentMap.firstOrNull()?.value as? ConstantValueContainer.Constant)?.value) {
                is IntValue -> ColorRGBa.fromHex(firstValue.value).toAWTColor()
                is StringValue -> try {
                    ColorRGBa.fromHex(firstValue.value).toAWTColor()
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
        }
    }

    object RGB : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            val firstArgument = argumentMap.firstArgument
            return when (val firstValue = (firstArgument?.value as? ConstantValueContainer.Constant)?.value) {
                is DoubleValue -> {
                    val doubles = argumentMap.colorComponents
                    when (doubles.size) {
                        1 -> rgb(doubles[0])
                        2 -> rgb(doubles[0], doubles[1])
                        3 -> rgb(doubles[0], doubles[1], doubles[2])
                        4 -> rgb(doubles[0], doubles[1], doubles[2], doubles[3])
                        else -> null
                    }?.toAWTColor()
                }
                is StringValue -> try {
                    ColorRGBa.fromHex(firstValue.value).toAWTColor()
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
        }
    }

    object ColorRGBaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorRGBa)
    }

    object ColorHSLaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toHSLa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLa)
    }

    object ColorHSVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toHSVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSVa)
    }

    object ColorLABaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toLABa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentRef(argumentMap, ::ColorLABa)
    }

    object ColorLCHABaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toLCHABa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentRef(argumentMap, ::ColorLCHABa)
    }

    object ColorLCHUVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toLCHUVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentRef(argumentMap, ::ColorLCHUVa)
    }

    object ColorLSHABaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color) { it.toLCHABa().toLSHABa() }

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentRef(argumentMap, ::ColorLSHABa)
    }

    object ColorLSHUVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color) { it.toLCHUVa().toLSHUVa() }

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentRef(argumentMap, ::ColorLSHUVa)
    }

    object ColorXSLaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toXSLa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLa)
    }

    object ColorXSVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toXSVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSVa)
    }

    object ColorXYZaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toXYZa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXYZa)
    }

    object ColorYxyaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color) { it.toXYZa().toRGBa() }

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorYxya)
    }

    object ColorHPLUVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toHPLUVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHPLUVa)
    }

    object ColorHSLUVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toHSLUVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLUVa)
    }

    object ColorOKHSLaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toOKHSLa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSLa)
    }

    object ColorOKHSVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toOKHSVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSVa)
    }

    object ColorOKLABaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toOKLABa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLABa)
    }

    object ColorOKLCHaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toOKLCHa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLCHa)
    }

    object ColorXSLUVaConstructor : ColorRGBaDescriptor() {
        override fun argumentsFromColor(color: Color) = argumentsFromColorSimple(color, ColorRGBa::toXSLUVa)

        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLUVa)
    }

    abstract fun argumentsFromColor(color: Color): Array<String>

    abstract fun colorFromArguments(argumentMap: ArgumentMap): Color?

    companion object {
        fun fromCallableDescriptor(targetDescriptor: CallableDescriptor): ColorRGBaDescriptor? {
            return when (targetDescriptor.getImportableDescriptor().name.asString()) {
                "fromHex" -> FromHex
                "rgb" -> RGB
                "ColorRGBa" -> ColorRGBaConstructor
                "hsl", "ColorHSLa" -> ColorHSLaConstructor
                "hsv", "ColorHSVa" -> ColorHSVaConstructor
                "ColorLABa" -> ColorLABaConstructor
                "ColorLCHABa" -> ColorLCHABaConstructor
                "ColorLCHUVa" -> ColorLCHUVaConstructor
                "ColorLSHABa" -> ColorLSHABaConstructor
                "ColorLSHUVa" -> ColorLSHUVaConstructor
                "ColorLUVa" -> ColorLABaConstructor
                "ColorXSLa" -> ColorXSLaConstructor
                "ColorXSVa" -> ColorXSVaConstructor
                "ColorXYZa" -> ColorXYZaConstructor
                "ColorYxya" -> ColorYxyaConstructor
                // ORX color models
                "ColorHPLUVa" -> ColorHPLUVaConstructor
                "ColorHSLUVa" -> ColorHSLUVaConstructor
                "ColorOKHSLa" -> ColorOKHSLaConstructor
                "ColorOKHSVa" -> ColorOKHSVaConstructor
                "ColorOKLABa" -> ColorOKLABaConstructor
                "ColorOKLCHa" -> ColorOKLCHaConstructor
                "ColorXSLUVa" -> ColorXSLUVaConstructor
                else -> null
            }
        }

        fun argumentsFromColorSimple(color: Color, conversionFunction: (ColorRGBa) -> ColorModel<*>): Array<String> {
            val colorVector = conversionFunction(color.toColorRGBa()).toVector4()
            return colorVector.toDoubleArray().formatNumbers()
        }

        fun colorFromArgumentsSimple(
            parametersToConstantsMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double) -> ColorModel<*>
        ): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> colorConstructor(doubles[0], doubles[1], doubles[2], 1.0)
                4 -> colorConstructor(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }

        fun <T> colorFromArgumentRef(
            parametersToConstantsMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double, ColorXYZa) -> T
        ): Color? where T : ColorModel<T>, T : ReferenceWhitePoint {
            val components = parametersToConstantsMap.toList().sortedBy { it.first.index }
            return when (components.size) {
                3 -> {
                    val doubles = components.map {
                        ((it.second as? ConstantValueContainer.Constant)?.value as? DoubleValue ?: return null).value
                    }.takeIf { it.size == 3 } ?: return null
                    colorConstructor(doubles[0], doubles[1], doubles[2], 1.0, ColorXYZa.NEUTRAL)
                }
                4, 5 -> {
                    val doubles = mutableListOf<Double>()
                    var ref: ColorXYZa = ColorXYZa.NEUTRAL
                    for ((_, constant) in components) {
                        when (constant) {
                            is ConstantValueContainer.Constant -> if (constant.value is DoubleValue) {
                                doubles.add(constant.value.value)
                            }
                            is ConstantValueContainer.WhitePoint -> ref = constant.value
                        }
                    }
                    when (doubles.size) {
                        3 -> colorConstructor(doubles[0], doubles[1], doubles[2], 1.0, ref)
                        4 -> colorConstructor(doubles[0], doubles[1], doubles[2], doubles[3], ref)
                        else -> null
                    }
                }
                else -> null
            }?.toAWTColor()
        }

        /** Returns parameter-argument pair with the positional index of 0. */
        val <V> Map<ValueParameterDescriptor, V>.firstArgument: Map.Entry<ValueParameterDescriptor, V>?
            get() {
                for (entry in this) {
                    if (entry.key.index == 0) return entry
                }
                return null
            }

        private val numberFormatter: NumberFormat = DecimalFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 3
        }

        private fun DoubleArray.formatNumbers() = Array<String>(size) {
            numberFormatter.format(this[it])
        }

        /**
         * @return all constant [Double]s in the map in canonical order.
         */
        val ArgumentMap.colorComponents: List<Double>
            get() = toList().sortedBy { it.first.index }.mapNotNull {
                ((it.second as? ConstantValueContainer.Constant)?.value as? DoubleValue)?.value
            }
    }
}
