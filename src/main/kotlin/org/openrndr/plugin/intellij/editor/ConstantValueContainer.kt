package org.openrndr.plugin.intellij.editor

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.constants.ConstantValue
import org.jetbrains.kotlin.resolve.constants.DoubleValue
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.openrndr.color.ColorXYZa
import org.openrndr.color.Linearity

internal sealed class ConstantValueContainer<out T> {
    class Constant<out T>(val value: ConstantValue<T>) : ConstantValueContainer<T>()
    class WhitePoint(val value: ColorXYZa) : ConstantValueContainer<ColorXYZa>()
    companion object {
        val REF = WhitePoint(ColorXYZa.NEUTRAL)
        val ALPHA = Constant(DoubleValue(1.0))
        val LINEARITY = Constant(
            EnumValue(
                ClassId.topLevel(FqName("org.openrndr.color.Linearity")),
                // Default value for Linearity in ColorRGBa
                Name.identifier(Linearity.UNKNOWN.name)
            )
        )
    }
}
