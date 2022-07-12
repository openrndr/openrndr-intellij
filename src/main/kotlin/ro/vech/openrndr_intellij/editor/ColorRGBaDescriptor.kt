package ro.vech.openrndr_intellij.editor

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toAWTColor
import ro.vech.openrndr_intellij.editor.ColorRGBaColorProvider.Companion.toColorRGBa
import java.awt.Color
import java.text.DecimalFormat
import java.text.NumberFormat

internal sealed class ColorRGBaDescriptor {
    object FromHex : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toRGBa
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

        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val firstArgument = parametersToConstantsMap.firstArgument
            return when (val firstValue = firstArgument?.value) {
                // An integer argument constant may either be an IntegerValueTypeConstant (because it's an integer literal)
                // or a TypedCompileTimeConstant (because it's a reference to an integer value or something like that)
                is IntegerValueTypeConstant -> {
                    val value = firstValue.toConstantValue(firstArgument.key.type).value as? Int ?: return null
                    ColorRGBa.fromHex(value).toAWTColor()
                }
                is TypedCompileTimeConstant -> {
                    when (val value = firstValue.constantValue.value) {
                        is Int -> ColorRGBa.fromHex(value).toAWTColor()
                        is String -> try {
                            ColorRGBa.fromHex(value).toAWTColor()
                        } catch (_: Exception) {
                            null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }

    object RGB : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val firstValue = parametersToConstantsMap.firstArgument?.value as? TypedCompileTimeConstant
            return when (firstValue?.type?.fqName?.asString()) {
                "kotlin.Double" -> {
                    val doubles = parametersToConstantsMap.colorComponents
                    when (doubles.size) {
                        1 -> rgb(doubles[0])
                        2 -> rgb(doubles[0], doubles[1])
                        3 -> rgb(doubles[0], doubles[1], doubles[2])
                        4 -> rgb(doubles[0], doubles[1], doubles[2], doubles[3])
                        else -> null
                    }?.toAWTColor()
                }
                "kotlin.String" -> try {
                    ColorRGBa.fromHex(firstValue.constantValue.value as? String ?: return null).toAWTColor()
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
        }
    }

    object HSV : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> hsv(doubles[0], doubles[1], doubles[2])
                4 -> hsv(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    }

    object ColorRGBaConstructor : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toRGBa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> ColorRGBa(doubles[0], doubles[1], doubles[2])
                4 -> ColorRGBa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    }

    object ColorHSLaConstructor : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toHSLa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> ColorHSLa(doubles[0], doubles[1], doubles[2])
                4 -> ColorHSLa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    }

    object ColorHSVaConstructor : ColorRGBaDescriptor() {
        override val conversionFunction = ColorRGBa::toHSVa
        override fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color? {
            val doubles = parametersToConstantsMap.colorComponents
            return when (doubles.size) {
                3 -> ColorHSVa(doubles[0], doubles[1], doubles[2])
                4 -> ColorHSVa(doubles[0], doubles[1], doubles[2], doubles[3])
                else -> null
            }?.toAWTColor()
        }
    }

    abstract val conversionFunction: (ColorRGBa) -> ColorModel<*>
    open fun argumentsFromColor(color: Color): Array<String> {
        val colorVector = conversionFunction(color.toColorRGBa()).toVector4()
        return colorVector.toDoubleArray().formatNumbers()
    }

    abstract fun colorFromArguments(parametersToConstantsMap: Map<ValueParameterDescriptor, CompileTimeConstant<*>?>): Color?

    companion object {
        fun fromCallableDescriptor(targetDescriptor: CallableDescriptor): ColorRGBaDescriptor? {
            return when (targetDescriptor.getImportableDescriptor().name.asString()) {
                "fromHex" -> FromHex
                "rgb" -> RGB
                "hsv" -> HSV
                "ColorRGBa" -> ColorRGBaConstructor
                "ColorHSLa" -> ColorHSLaConstructor
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

        val Map<ValueParameterDescriptor, CompileTimeConstant<*>?>.colorComponents: List<Double>
            get() = toList()
                // Sort to canonical order
                .sortedBy { it.first.index }
                .mapNotNull { (it.second as? TypedCompileTimeConstant)?.constantValue?.value as? Double }
    }
}
