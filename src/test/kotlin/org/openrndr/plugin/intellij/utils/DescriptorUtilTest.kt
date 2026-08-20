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
        assertEquals(0 to 4, parseOpenrndrColorMajorMinor("/x/openrndr-color-0.4.5.jar")) // common metadata, no class.
        assertEquals(1 to 2, parseOpenrndrColorMajorMinor("/x/openrndr-color-jvm-1.2.3.jar"))

        // Non-JVM Kotlin Multiplatform targets: klibs and other platform classifiers must resolve too.
        assertEquals(0 to 5, parseOpenrndrColorMajorMinor("/x/openrndr-color-js-0.5.0.klib"))
        assertEquals(0 to 4, parseOpenrndrColorMajorMinor("/x/openrndr-color-js-ir-0.4.5.klib")) // multi-token class.
        assertEquals(0 to 5, parseOpenrndrColorMajorMinor("/x/openrndr-color-iosx64-0.5.0-alpha4.klib"))
        assertEquals(0 to 5, parseOpenrndrColorMajorMinor("/x/openrndr-color-metadata-0.5.0.jar"))
        // A realistic Gradle cache path: the match must come from the file name, not the version-less directory.
        assertEquals(
            0 to 5,
            parseOpenrndrColorMajorMinor(
                "/h/.gradle/caches/modules-2/files-2.1/org.openrndr/openrndr-color-js/0.5.0/ab/openrndr-color-js-0.5.0.klib"
            )
        )

        // Must not be confused by other artifacts that merely contain a version number.
        assertNull(parseOpenrndrColorMajorMinor("/x/orx-color-jvm-0.5.0.jar"))
        assertNull(parseOpenrndrColorMajorMinor("/x/openrndr-colorbuffer-jvm-1.0.0.jar"))
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
