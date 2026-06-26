package org.openrndr.plugin.intellij.editor

import org.jetbrains.kotlin.utils.threadLocal
import org.openrndr.color.*
import org.openrndr.extra.color.spaces.*
import org.openrndr.plugin.intellij.utils.ArgumentMap
import org.openrndr.plugin.intellij.utils.ColorUtil.defaultColorRGBa
import org.openrndr.plugin.intellij.utils.ColorUtil.toAWTColor
import org.openrndr.plugin.intellij.utils.ColorUtil.toColorRGBa
import org.openrndr.plugin.intellij.utils.colorComponents
import org.openrndr.plugin.intellij.utils.linearity
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

/**
 * Bridges a textual openrndr color expression and an AWT [Color], one enum constant per supported
 * constructor / factory function (`ColorRGBa(...)`, `rgb(...)`, `ColorRGBa.fromHex(...)`, `ColorHSVa(...)`,
 * every orx color space, …).
 *
 * Each constant knows how to go both ways:
 *  - [colorFromArguments] turns the already-resolved call arguments into the [Color] shown in the gutter.
 *  - [argumentsFromColor] turns a color the user picked back into the argument strings for that color model,
 *    which the color picker writes into the source.
 *
 * The matching from a resolved call to a descriptor happens by simple name in [fromCallableName].
 */
internal enum class ColorRGBaDescriptor {
    /** `ColorRGBa.fromHex(...)` — accepts a hex string (`"#ff00ff"`) or an `Int` literal (`0xff00ff`). */
    FromHex {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity): Array<String> {
            val hex = color.rgb.let {
                // If alpha is 0xff, we won't need it
                if (it and -0x1000000 == -0x1000000) {
                    (it and 0xffffff).toString(16).padStart(6, '0')
                } else {
                    // We need to rotate it from AARRGGBB to RRGGBBAA
                    it.rotateLeft(8).toUInt().toString(16).padStart(8, '0')
                }
            }
            return arrayOf("\"#$hex\"")
        }

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            val anyArgumentValue = argumentMap.values.firstOrNull() as? ConstantValueContainer.Constant
            return when (val firstValue = anyArgumentValue?.value) {
                is Int -> ColorRGBa.fromHex(firstValue).toAWTColor()
                is String -> try {
                    ColorRGBa.fromHex(firstValue).toAWTColor()
                } catch (_: Exception) {
                    null
                }

                else -> null
            }
        }

        // `fromHex` always yields an sRGB color (and we write a hex string, not components, so this is unused
        // by the write-back; it is kept honest for documentation).
        override val defaultLinearity: Linearity = Linearity.SRGB
    },

    /** The `rgb(...)` shorthand factory function: 1–4 doubles, or a hex string. */
    RGB {
        // Like the ColorRGBa constructor, we honor the call's actual linearity (here detected from the
        // project's openrndr version, since `rgb()` has no linearity argument) so write-back stays in that space.
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) =
            argumentsFromColorSimple(color, linearity, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            // The argument types for either call are homogenous, so it doesn't matter which argument the map
            // returns for us as we're only interested in knowing the type
            val anyArgumentValue = argumentMap.values.firstOrNull() as? ConstantValueContainer.Constant
            return when (val firstValue = anyArgumentValue?.value) {
                // Build the ColorRGBa ourselves rather than calling openrndr's `rgb()`, so the swatch uses the
                // linearity of the *project's* openrndr (detected and stored by computeValueArguments): `rgb()`'s
                // double overloads switched from sRGB to linear in openrndr 0.5.0. The 1- and 2-argument forms
                // are gray (r=g=b); alpha is included whenever present, so we normally see 2 or 4 components.
                is Double -> argumentMap.linearity.let { linearity ->
                    argumentMap.colorComponents.let {
                        when (it.size) {
                            1 -> ColorRGBa(it[0], it[0], it[0], 1.0, linearity)
                            2 -> ColorRGBa(it[0], it[0], it[0], it[1], linearity)
                            3 -> ColorRGBa(it[0], it[1], it[2], 1.0, linearity)
                            4 -> ColorRGBa(it[0], it[1], it[2], it[3], linearity)
                            else -> null
                        }?.toAWTColor()
                    }
                }

                // openrndr 0.5.0's `rgb(red, green, blue, alpha = 255)` Int overload: 0-255 sRGB regardless of
                // version. Each explicit Int is scaled by 255; an omitted alpha is the normalized 1.0 our ALPHA
                // default already supplies as a Double, so we pass Doubles through unscaled.
                is Int -> argumentMap.toList().sortedBy { it.first.index }.mapNotNull {
                    when (val component = (it.second as? ConstantValueContainer.Constant)?.value) {
                        is Int -> component / 255.0
                        is Double -> component
                        else -> null
                    }
                }.let {
                    when (it.size) {
                        3 -> ColorRGBa(it[0], it[1], it[2], 1.0, Linearity.SRGB)
                        4 -> ColorRGBa(it[0], it[1], it[2], it[3], Linearity.SRGB)
                        else -> null
                    }?.toAWTColor()
                }

                is String -> try {
                    ColorRGBa.fromHex(firstValue).toAWTColor()
                } catch (_: Exception) {
                    null
                }

                else -> null
            }
        }

        // Unused: write-back uses the detected per-call linearity above. Kept to satisfy the abstract member.
        override val defaultLinearity: Linearity = Linearity.SRGB
    },

    // @formatter:off
    // Below: one constant per color-model constructor.
    //
    // They all delegate to the `*Simple`/`*Ref` companion helpers,
    // differing only in which openrndr conversion function they use and (for the `ColorXYZa?` ref
    // models) whether they thread a reference white point through.
    //
    // The `argumentsFromColor`/`colorFromArguments` round-trip is intended to be
    // a lossless modulo floating-point error; see [defaultLinearity] for why.
    ColorRGBaConstructor {
        // Unlike every other model, `ColorRGBa` takes its linearity as an explicit argument, so we honor the
        // call's actual linearity in both directions: render the components in that linearity (the swatch shows
        // it gamma-encoded via toAWTColor), and write picked colors back in that same linearity so the result
        // round-trips and the user's `Linearity.SRGB` / `Linearity.LINEAR` choice is preserved.
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) =
            argumentsFromColorSimple(color, linearity, ColorRGBa::toRGBa)

        override fun colorFromArguments(argumentMap: ArgumentMap): Color? {
            val linearity = argumentMap.linearity
            return colorFromArgumentsSimple(argumentMap) { r, g, b, a -> ColorRGBa(r, g, b, a, linearity) }
        }

        override val defaultLinearity: Linearity = ConstantValueContainer.DEFAULT_LINEARITY
    },
    ColorHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toHSLa().toRGBa().linearity
    },
    ColorHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toHSVa().toRGBa().linearity
    },
    ColorLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLABa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLABa().toRGBa().linearity
    },
    ColorLCHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHABa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHABa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLCHABa().toRGBa().linearity
    },
    ColorLCHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLCHUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLCHUVa().toRGBa().linearity
    },
    ColorLSHABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHABa(ref!!).toLSHABa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHABa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLCHABa().toLSHABa().toRGBa().linearity
    },
    ColorLSHUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLCHUVa(ref!!).toLSHUVa() }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLSHUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLCHUVa().toLSHUVa().toRGBa().linearity
    },
    ColorLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { it.toLUVa(ref!!) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsRef(argumentMap, ::ColorLUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toLUVa().toRGBa().linearity
    },
    ColorXSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toXSLa().toRGBa().linearity
    },
    ColorXSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toXSVa().toRGBa().linearity
    },
    ColorXYZaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXYZa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXYZa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toXYZa().toRGBa().linearity
    },
    ColorYxyaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity) { ColorYxya.fromXYZa(it.toXYZa()) }
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorYxya)
        override val defaultLinearity: Linearity = ColorYxya.fromXYZa(defaultColorRGBa.toXYZa()).toRGBa().linearity
    },
    ColorHPLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHPLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHPLUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toHPLUVa().toRGBa().linearity
    },
    ColorHSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toHSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorHSLUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toHSLUVa().toRGBa().linearity
    },
    ColorOKHSLaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKHSLa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSLa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toOKHSLa().toRGBa().linearity
    },
    ColorOKHSVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKHSVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKHSVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toOKHSVa().toRGBa().linearity
    },
    ColorOKLABaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKLABa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLABa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toOKLABa().toRGBa().linearity
    },
    ColorOKLCHaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toOKLCHa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorOKLCHa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toOKLCHa().toRGBa().linearity
    },
    ColorXSLUVaConstructor {
        override fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity) = argumentsFromColorSimple(color, defaultLinearity, ColorRGBa::toXSLUVa)
        override fun colorFromArguments(argumentMap: ArgumentMap) = colorFromArgumentsSimple(argumentMap, ::ColorXSLUVa)
        override val defaultLinearity: Linearity = defaultColorRGBa.toXSLUVa().toRGBa().linearity
    };
    // @formatter:on

    /**
     * @param ref Only present for color models where the reference color is used in the constructor,
     * regardless of whether the user specifies it or not.
     * @param linearity the linearity in which the resulting components should be expressed. Only the
     * [ColorRGBaConstructor] varies it (to match the call's `linearity` argument); every other model has a
     * component space with a fixed linearity and ignores it in favor of its own [defaultLinearity].
     * @return new arguments (in canonical order) to be used to replace the old arguments
     */
    abstract fun argumentsFromColor(color: Color, ref: ColorXYZa?, linearity: Linearity): Array<String>

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
        /**
         * @param callableName the simple name of the resolved function/constructor,
         * e.g. `fromHex`, `rgb`, or `ColorRGBa`
         * (see [org.openrndr.plugin.intellij.utils.callableShortName]).
         */
        fun fromCallableName(callableName: String?): ColorRGBaDescriptor? {
            return when (callableName) {
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

        /**
         * Converts [color] into the four component strings of a target color model: take the AWT color into a
         * [ColorRGBa] of the given [linearity], run [conversionFunction] to reach the target model, and format
         * its `(c0, c1, c2, alpha)` vector. Used by every descriptor whose constructor takes plain components.
         */
        fun argumentsFromColorSimple(
            color: Color,
            linearity: Linearity,
            conversionFunction: (ColorRGBa) -> ColorModel<*>
        ): Array<String> {
            val colorVector = conversionFunction(color.toColorRGBa(linearity)).toVector4()
            return colorVector.toDoubleArray().formatNumbers()
        }

        /**
         * Builds the [Color] for a component-based color model from its resolved arguments by feeding the 3
         * (alpha defaulted to 1.0) or 4 [colorComponents][org.openrndr.plugin.intellij.utils.colorComponents]
         * into [colorConstructor]. Returns `null` for any other argument count.
         */
        fun colorFromArgumentsSimple(
            argumentMap: ArgumentMap,
            colorConstructor: (Double, Double, Double, Double) -> ColorModel<*>
        ): Color? = argumentMap.colorComponents.let {
            when (it.size) {
                3 -> colorConstructor(it[0], it[1], it[2], 1.0)
                4 -> colorConstructor(it[0], it[1], it[2], it[3])
                else -> null
            }?.toAWTColor()
        }

        /**
         * Like [colorFromArgumentsSimple] but for models that carry a reference white point (LAB, LCH, LUV, …).
         * Separates the numeric components from the resolved `ref` white point in [argumentMap] and passes the
         * white point (defaulting to [ColorXYZa.NEUTRAL]) to [colorConstructor] alongside the components.
         */
        fun <T> colorFromArgumentsRef(
            argumentMap: ArgumentMap,
            colorConstructor: (Double, Double, Double, Double, ColorXYZa) -> T
        ): Color? where T : ColorModel<T>, T : ReferenceWhitePoint {
            val components = argumentMap.toList().sortedBy { it.first.index }
            if (components.size !in 3..5) return null
            val doubles = mutableListOf<Double>()
            var ref: ColorXYZa = ColorXYZa.NEUTRAL
            for ((_, constant) in components) {
                when (constant) {
                    is ConstantValueContainer.Constant -> (constant.value as? Double)?.let { doubles.add(it) }
                    is ConstantValueContainer.WhitePoint -> ref = constant.value
                    // Reference-white-point models have no `linearity` argument, so this never occurs here; it
                    // is matched only to keep the `when` exhaustive over the sealed hierarchy.
                    is ConstantValueContainer.LinearityArg -> {}
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
    }
}
