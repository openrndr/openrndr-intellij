package org.openrndr.plugin.intellij.editor

import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.openrndr.color.ColorXYZa
import org.openrndr.color.Linearity
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isAlpha
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isLinearity
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isRef

internal sealed class ConstantValueContainer<out T> {
    class Constant<out T>(val value: ConstantValue<T>) : ConstantValueContainer<T>()
    class WhitePoint(val value: ColorXYZa) : ConstantValueContainer<ColorXYZa>()
    companion object {
        private val REF = WhitePoint(ColorXYZa.NEUTRAL)
        private val ALPHA = Constant(DoubleValue(1.0))
        private val LINEARITY = Constant(
            EnumValue(
                ClassId.topLevel(FqName("org.openrndr.color.Linearity")),
                // Default value for Linearity in ColorRGBa
                Name.identifier(Linearity.UNKNOWN.name)
            )
        )

        fun ValueParameterDescriptor.getDefaultValueIfKnown(): ConstantValueContainer<*>? {
            if (!hasDefaultValue()) return null
            return when {
                isAlpha() -> ALPHA
                isRef() -> REF
                isLinearity() -> LINEARITY
                else -> null
            }
        }
    }
}