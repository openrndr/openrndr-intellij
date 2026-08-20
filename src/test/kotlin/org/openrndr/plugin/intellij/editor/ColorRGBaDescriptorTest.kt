package org.openrndr.plugin.intellij.editor

import junit.framework.TestCase
import org.openrndr.color.ColorRGBa
import org.openrndr.color.Linearity
import org.openrndr.plugin.intellij.utils.ArgumentMap
import org.openrndr.plugin.intellij.utils.ColorArgument
import org.openrndr.plugin.intellij.utils.ColorUtil.toAWTColor
import org.openrndr.plugin.intellij.utils.hasIntComponents
import java.awt.Color

/**
 * Descriptor-level unit tests that don't need the IDE fixture. They exercise openrndr 0.5.0's Int
 * `rgb(red, green, blue, alpha = 255)` overload, which our bundled openrndr (0.4.5) can't resolve, so we build
 * the resolved [ArgumentMap] by hand. [colorFromArguments][ColorRGBaDescriptor.colorFromArguments] only cares
 * about each argument's index and type, not its name.
 */
class ColorRGBaDescriptorTest : TestCase() {
    private fun arguments(vararg values: Any): ArgumentMap =
        values.mapIndexed { i, v -> ColorArgument(i, "c$i") to ConstantValueContainer.Constant(v) }.toMap()

    fun testRgbIntOverloadRendersScaledSrgb() {
        // rgb(255, 0, 0, 255) -> opaque red.
        assertEquals(
            ColorRGBa(1.0, 0.0, 0.0, 1.0, Linearity.SRGB).toAWTColor(),
            ColorRGBaDescriptor.RGB.colorFromArguments(arguments(255, 0, 0, 255))
        )
        // rgb(255, 0, 0): the omitted alpha is the normalized 1.0 (a Double) our resolver injects, so it passes
        // through unscaled while the explicit Ints are divided by 255.
        assertEquals(
            ColorRGBa(1.0, 0.0, 0.0, 1.0, Linearity.SRGB).toAWTColor(),
            ColorRGBaDescriptor.RGB.colorFromArguments(arguments(255, 0, 0, 1.0))
        )
        // rgb(128, 64, 32, 200): partial channels and alpha.
        assertEquals(
            ColorRGBa(128 / 255.0, 64 / 255.0, 32 / 255.0, 200 / 255.0, Linearity.SRGB).toAWTColor(),
            ColorRGBaDescriptor.RGB.colorFromArguments(arguments(128, 64, 32, 200))
        )
    }

    fun testRgbIntOverloadIsSrgbNotLinear() {
        // A mid-gray Int rgb is plain sRGB and must NOT be gamma-encoded the way the linear double overload is.
        val gray = ColorRGBaDescriptor.RGB.colorFromArguments(arguments(128, 128, 128, 255))
        assertEquals(ColorRGBa(128 / 255.0, 128 / 255.0, 128 / 255.0, 1.0, Linearity.SRGB).toAWTColor(), gray)
        assertFalse(
            "Int rgb must be sRGB, distinct from the linear interpretation of the same components",
            gray == ColorRGBa(128 / 255.0, 128 / 255.0, 128 / 255.0, 1.0, Linearity.LINEAR).toAWTColor()
        )
    }

    fun testHasIntComponents() {
        assertTrue(arguments(255, 0, 0, 255).hasIntComponents)
        assertFalse(arguments(0.5, 0.5, 0.5, 1.0).hasIntComponents)
    }

    fun testIntRgbWriteBackDropsOpaqueAlpha() {
        // A fully-opaque pick omits alpha so the call falls back to the rgb(...) default of 255.
        assertEquals(listOf("12", "34", "56"), intRgbArguments(Color(12, 34, 56, 255)).toList())
        // A translucent pick keeps the explicit alpha.
        assertEquals(listOf("12", "34", "56", "128"), intRgbArguments(Color(12, 34, 56, 128)).toList())
    }
}
