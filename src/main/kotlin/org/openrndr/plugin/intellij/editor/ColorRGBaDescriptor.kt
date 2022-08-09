package org.openrndr.plugin.intellij.editor

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.jetbrains.kotlin.resolve.constants.IntValue
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.jetbrains.kotlin.utils.threadLocal
import org.openrndr.color.*
import org.openrndr.extra.color.spaces.*
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.toAWTColor
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.toColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

internal typealias ArgumentMap = Map<ValueParameterDescriptor, ConstantValueContainer<*>>

private val DEFAULT_COLORRGBA = ColorRGBa(1.0, 1.0, 1.0, 1.0, Linearity.UNKNOWN)

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
            val anyArgumentValue = argumentMap.values.firstOrNull() as? ConstantValueContainer.Constant
            return when (val firstValue = anyArgumentValue?.value) {
                is IntValue -> ColorRGBa.fromHex(firstValue.value).toAWTColor()
                is StringValue -> try {
                    ColorRGBa.fromHex(firstValue.value).toAWTColor()
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
        }

        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.linearity
    },
    RGB {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) =
            argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            // The argument types for either call are homogenous so it doesn't matter which argument the map
            // returns for us as we're only interested in knowing the type
            val anyArgumentValue = argumentMap.values.firstOrNull() as? ConstantValueContainer.Constant
            return when (val firstValue = anyArgumentValue?.value) {
                is DoubleValue -> argumentMap.colorComponents.let {
                    // Alpha is always included so we only ever have either 2 or 4 components, but whatever
                    when (it.size) {
                        1 -> rgb(it[0])
                        2 -> rgb(it[0], it[1])
                        3 -> rgb(it[0], it[1], it[2])
                        4 -> rgb(it[0], it[1], it[2], it[3])
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

        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.linearity
    },

    // @formatter:off
    ColorRGBaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toRGBa)
        override fun colorFromArguments(argumentMap: ArgumentMap): Color? = colorFromArgumentsSimple(argumentMap, ::ColorRGBa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.linearity
    },
    ColorHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toHSLa().toRGBa().linearity
    },
    ColorHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toHSVa().toRGBa().linearity
    },
    ColorLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLABa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLABa().toRGBa().linearity
    },
    ColorLCHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHABa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLCHABa().toRGBa().linearity
    },
    ColorLCHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLCHUVa().toRGBa().linearity
    },
    ColorLSHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHABa(ref!!).toLSHABa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHABa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLCHABa().toLSHABa().toRGBa().linearity
    },
    ColorLSHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHUVa(ref!!).toLSHUVa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLCHUVa().toLSHUVa().toRGBa().linearity
    },
    ColorLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { it.toLUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toLUVa().toRGBa().linearity
    },
    ColorXSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toXSLa().toRGBa().linearity
    },
    ColorXSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toXSVa().toRGBa().linearity
    },
    ColorXYZaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXYZa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXYZa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toXYZa().toRGBa().linearity
    },
    ColorYxyaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity) { ColorYxya.fromXYZa(it.toXYZa()) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorYxya)
        override val defaultLinearity: Linearity = ColorYxya.fromXYZa(DEFAULT_COLORRGBA.toXYZa()).toRGBa().linearity
    },
    ColorHPLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHPLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHPLUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toHPLUVa().toRGBa().linearity
    },
    ColorHSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toHSLUVa().toRGBa().linearity
    },
    ColorOKHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSLa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toOKHSLa().toRGBa().linearity
    },
    ColorOKHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toOKHSVa().toRGBa().linearity
    },
    ColorOKLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKLABa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLABa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toOKLABa().toRGBa().linearity
    },
    ColorOKLCHaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKLCHa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLCHa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toOKLCHa().toRGBa().linearity
    },
    ColorXSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLUVa)
        override val defaultLinearity: Linearity = DEFAULT_COLORRGBA.toXSLUVa().toRGBa().linearity
    };
    // @formatter:on

    /**
     * @param ref Only present for color models where the reference color is used in the constructor,
     * regardless of whether the user specifies it or not.
     * @return new arguments (in canonical order) to be used to replace the old arguments
     */
    abstract fun argumentsFromColor(color: Color, ref: ColorXYZa?): Array<String>

    /** The resulting Color from an [ArgumentMap]. */
    abstract fun colorFromArguments(argumentMap: ArgumentMap): Color?

    /**
     * Or in other words, of what [Linearity] ColorRGBa do you get when you call [ColorModel.toRGBa]
     * on the given ColorModel implementation? This is used on the assumption that converting the
     * resulting ColorRGBa with the same linearity back to the previous ColorModel implementation
     * will yield a practically identical color (floating-point accuracy errors notwithstanding).
     * Because one can observe that the Linearity of the ColorRGBa used to convert to a different
     * ColorModel can have an effect on the resulting color, e.g. [ColorHSLa.fromRGBa] calls
     * [ColorRGBa.toSRGB] in the function body.
     */
    abstract val defaultLinearity: Linearity

    companion object {
        fun fromCallableDescriptor(targetDescriptor: CallableDescriptor): ColorRGBaDescriptor? {
            return when (targetDescriptor.getImportableDescriptor().name.identifier) {
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

        fun argumentsFromColorSimple(
            color: Color, linearity: Linearity, conversionFunction: (ColorRGBa) -> ColorModel<*>
        ): Array<String> {
            val colorVector = conversionFunction(color.toColorRGBa(linearity)).toVector4()
            return colorVector.toDoubleArray().formatNumbers()
        }

        fun colorFromArgumentsSimple(
            argumentMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double) -> ColorModel<*>
        ): Color? = argumentMap.colorComponents.let {
            when (it.size) {
                3 -> colorConstructor(it[0], it[1], it[2], 1.0)
                4 -> colorConstructor(it[0], it[1], it[2], it[3])
                else -> null
            }?.toAWTColor()
        }

        fun <T> colorFromArgumentsRef(
            argumentMap: ArgumentMap, colorConstructor: (Double, Double, Double, Double, ColorXYZa) -> T
        ): Color? where T : ColorModel<T>, T : ReferenceWhitePoint {
            val components = argumentMap.toList().sortedBy { it.first.index }
            if (components.size !in 3..5) return null
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
            return when (doubles.size) {
                3 -> colorConstructor(doubles[0], doubles[1], doubles[2], 1.0, ref)
                4 -> colorConstructor(doubles[0], doubles[1], doubles[2], doubles[3], ref)
                else -> null
            }?.toAWTColor()
        }

        /** [ThreadLocal]-wrapped [DecimalFormat], otherwise it wouldn't be thread-safe. */
        private val decimalFormat by threadLocal {
            DecimalFormat("0.0##", DecimalFormatSymbols(Locale.US))
        }

        private fun DoubleArray.formatNumbers() = Array<String>(size) {
            decimalFormat.format(this[it])
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