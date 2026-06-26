package org.openrndr.plugin.intellij.utils

import junit.framework.TestCase
import org.openrndr.color.Linearity

/**
 * Pure unit tests for the openrndr-color version detection that drives the linearity of the `rgb(...)`
 * shorthand. The IDE-integration half (reading the version off a resolved `rgb` symbol's jar) is covered by
 * `ColorRGBaColorProviderTest`; here we pin the parsing and the version → linearity mapping in isolation.
 */
class DescriptorUtilTest : TestCase() {
    fun testParseOpenrndrColorVersionFromJarPath() {
        assertEquals(
            0 to 4,
            parseOpenrndrColorMajorMinor("/h/.m2/org/openrndr/openrndr-color-jvm/0.4.5/openrndr-color-jvm-0.4.5.jar!/x")
        )
        assertEquals(0 to 5, parseOpenrndrColorMajorMinor("/x/openrndr-color-jvm-0.5.0-alpha4.jar!/x"))
        assertEquals(0 to 5, parseOpenrndrColorMajorMinor("/x/openrndr-color-jvm-0.5.0-SNAPSHOT.jar!/x"))
        assertEquals(0 to 4, parseOpenrndrColorMajorMinor("/x/openrndr-color-0.4.5.jar")) // no -jvm classifier
        assertEquals(1 to 2, parseOpenrndrColorMajorMinor("/x/openrndr-color-jvm-1.2.3.jar"))
        // Must not be confused by another library that merely contains a version number.
        assertNull(parseOpenrndrColorMajorMinor("/x/orx-color-jvm-0.5.0.jar"))
        assertNull(parseOpenrndrColorMajorMinor("garbage"))
    }

    fun testRgbShorthandLinearityByVersion() {
        // openrndr 0.5.0 flipped the double rgb() overloads from sRGB to linear (commit 01d4f82).
        assertEquals(Linearity.SRGB, rgbShorthandLinearity(0 to 4))
        assertEquals(Linearity.LINEAR, rgbShorthandLinearity(0 to 5))
        assertEquals(Linearity.LINEAR, rgbShorthandLinearity(0 to 6))
        assertEquals(Linearity.LINEAR, rgbShorthandLinearity(1 to 0))
        // Unknown version falls back to sRGB (openrndr's long-standing behavior and our bundled version).
        assertEquals(Linearity.SRGB, rgbShorthandLinearity(null))
    }
}
