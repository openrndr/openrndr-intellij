package ro.vech.openrndr_intellij.editor

import com.intellij.ui.ColorHexUtil
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
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

        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color? {
            return when (val hex = parametersToConstantsMap.firstArgument?.value?.constantValue?.value) {
                is Int -> ColorRGBa.fromHex(hex).toAWTColor()
                is String -> ColorHexUtil.fromHexOrNull(hex)
                else -> null
            }
        }
    },
    RGB {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color? {
            val firstValue = parametersToConstantsMap.firstArgument?.value
            return when (firstValue?.type?.fqName) {
                FqName("kotlin.Double") -> {
                    val doubles = parametersToConstantsMap.colorComponents
                    when (doubles.size) {
                        1 -> rgb(doubles[0])
                        2 -> rgb(doubles[0], doubles[1])
                        3 -> rgb(doubles[0], doubles[1], doubles[2])
                        4 -> rgb(doubles[0], doubles[1], doubles[2], doubles[3])
                        else -> null
                    }?.toAWTColor()
                }
                FqName("kotlin.String") -> ColorHexUtil.fromHexOrNull(
                    firstValue.constantValue.value as? String ?: return null
                )
                else -> null
            }
        }
    },
    HSV {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> hsv(doubles[0], doubles[1], doubles[2])
                4 -> hsv(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    },
    ColorRGBaConstructor {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> ColorRGBa(doubles[0], doubles[1], doubles[2])
                4 -> ColorRGBa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    },
    ColorHSVaConstructor {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> ColorHSVa(doubles[0], doubles[1], doubles[2])
                4 -> ColorHSVa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    };

    abstract val conversionFunction: (ColorRGBa) -> CastableToVector4
    open fun argumentsFromColor(color: Color): Array<String> {
        val colorVector = conversionFunction(color.toColorRGBa()).toVector4()
        return colorVector.toDoubleArray().formatNumbers()
    }

    abstract fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>): Color?

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

        /** Returns parameter-argument pair with the positional index of 0. */
        val <T> Map<ValueParameterDescriptor, T>.firstArgument: Map.Entry<ValueParameterDescriptor, T>?
            get() {
                for (entry in this) {
                    if (entry.key.index == 0) return entry
                }
                return null
            }

        private val numberFormatter: NumberFormat = DecimalFormat.getNumberInstance().also {
            it.minimumFractionDigits = 1
            it.maximumFractionDigits = 3
        }

        fun DoubleArray.formatNumbers() = Array<String>(size) {
            numberFormatter.format(this[it])
        }

        val Map<ValueParameterDescriptor, TypedCompileTimeConstant<*>?>.colorComponents: List<Double>
            get() = toList()
                // Sort to canonical order
                .sortedBy { it.first.index }
                .filter { it.first.type.fqName != FqName("org.openrndr.color.Linearity") }
                .mapNotNull { it.second?.constantValue?.value as? Double }
    }
}
