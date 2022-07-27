package org.openrndr.plugin.intellij.editor

import com.jetbrains.rd.util.firstOrNull
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.resolve.constants.*
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import org.openrndr.extra.color.spaces.*
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.Companion.toAWTColor
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.Companion.toColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.NumberFormat

internal typealias ArgumentMap = Map<ValueParameterDescriptor, ConstantValueContainer<*>>

internal enum class ColorRGBaDescriptor {
    FromHex {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?): Array<String> {
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
    },
    RGB {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) =
            argumentsFromColorSimple(color, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            // The argument types for either call are homogenous so it doesn't matter which argument the map
            // returns for us as we're only interested in knowing the type
            val anyArgument = argumentMap.firstOrNull()
            return when (val firstValue = (anyArgument?.value as? ConstantValueContainer.Constant)?.value) {
                is DoubleValue -> {
                    val doubles = argumentMap.colorComponents
                    // TODO: Isn't alpha always included?
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
    },
    ColorRGBaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) =
            argumentsFromColorSimple(color, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            val components = argumentMap.toList().sortedBy { it.first.index }
            if (components.size != 5) return null
            val doubles = mutableListOf<Double>()
            var linearity: Linearity = Linearity.UNKNOWN
            for ((_, constant) in components) {
                if (constant !is ConstantValueContainer.Constant) return null
                when (val value = constant.value) {
                    is DoubleValue -> doubles.add(value.value)
                    is EnumValue -> linearity = Linearity.valueOf(value.enumEntryName.identifier)
                }
            }
            return ColorRGBa(doubles[0], doubles[1], doubles[2], doubles[3], linearity).toAWTColor()
        }
    },

    // @formatter:off
    ColorHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLa)
    },
    ColorHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSVa)
    },
    ColorLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLABa)
    },
    ColorLCHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLCHABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHABa)
    },
    ColorLCHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLCHUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHUVa)
    },
    ColorLSHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLCHABa(ref!!).toLSHABa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHABa)
    },
    ColorLSHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLCHUVa(ref!!).toLSHUVa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHUVa)
    },
    ColorLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toLUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLUVa)
    },
    ColorXSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toXSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLa)
    },
    ColorXSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toXSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSVa)
    },
    ColorXYZaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toXYZa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXYZa)
    },
    ColorYxyaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color) { it.toXYZa().toRGBa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorYxya)
    },
    ColorHPLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toHPLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHPLUVa)
    },
    ColorHSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toHSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLUVa)
    },
    ColorOKHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toOKHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSLa)
    },
    ColorOKHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toOKHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSVa)
    },
    ColorOKLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toOKLABa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLABa)
    },
    ColorOKLCHaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toOKLCHa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLCHa)
    },
    ColorXSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, ColorRGBa::toXSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLUVa)
    };
    // @formatter:on

    /**
     * @param ref Only present for color models where the reference color is used in the constructor,
     * regardless of whether the user specifies it or not.
     */
    abstract fun argumentsFromColor(color: Color, ref: ColorXYZa?): Array<String>

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
                "ColorLUVa" -> ColorLUVaConstructor
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
            argumentMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double) -> ColorModel<*>
        ): Color? {
            val doubles = argumentMap.colorComponents
            return when (doubles.size) {
                3 -> colorConstructor(doubles[0], doubles[1], doubles[2], 1.0)
                4 -> colorConstructor(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }

        fun <T> colorFromArgumentsRef(
            argumentMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double, ColorXYZa) -> T
        ): Color? where T : ColorModel<T>, T : ReferenceWhitePoint {
            val components = argumentMap.toList().sortedBy { it.first.index }
            return when (components.size) {
                3 -> {
                    // TODO: Is this ever reached?
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
