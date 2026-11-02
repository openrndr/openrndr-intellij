package org.openrndr.plugin.intellij

import junit.framework.TestCase
import org.openrndr.plugin.intellij.OrxModules.OrxModule
import java.nio.file.Files

/** Finding the orx modules a guide program imports, and adding them to a template build.gradle.kts. */
class OrxModulesTest : TestCase() {

    fun `test packages that do not follow the module names`() {
        assertEquals(OrxModule("orx-jumpflood", "jumpflood"), OrxModules.moduleOf("org.openrndr.extra.jumpfill.JumpFlooder"))
        assertEquals(OrxModule("orx-image-fit", "image-fit"), OrxModules.moduleOf("org.openrndr.extra.imageFit.imageFit"))
        assertEquals(OrxModule("orx-g-code", "g-code"), OrxModules.moduleOf("org.openrndr.extra.gcode.Generator"))
        // The catalog key differs from the artifact
        assertEquals(OrxModule("orx-mesh", "mesh-core"), OrxModules.moduleOf("org.openrndr.extra.mesh.IMeshData"))
        // Not under org.openrndr.extra
        assertEquals(OrxModule("orx-panel", "panel"), OrxModules.moduleOf("org.openrndr.panel.elements.Button"))
    }

    fun `test sub packages and star imports`() {
        assertEquals("orx-fx", OrxModules.moduleOf("org.openrndr.extra.fx.blur.GaussianBlur")?.artifact)
        assertEquals("orx-fx", OrxModules.moduleOf("org.openrndr.extra.fx.blur.")?.artifact)
        assertEquals("orx-color", OrxModules.moduleOf("org.openrndr.extra.color.presets.ORANGE_RED")?.artifact)
    }

    fun `test OPENRNDR itself is not an orx module`() {
        assertNull(OrxModules.moduleOf("org.openrndr.application"))
        assertNull(OrxModules.moduleOf("org.openrndr.color.ColorRGBa"))
        assertNull(OrxModules.moduleOf("kotlin.math.sin"))
    }

    fun `test imported modules of a guide program`() {
        val code = """
            import org.openrndr.application
            import org.openrndr.extra.gui.GUI
            import org.openrndr.extra.parameters.DoubleParameter
            import org.openrndr.extra.fx.blur.*
            import org.openrndr.extra.noise.simplex as noise

            fun main() = application { program { } }
        """.trimIndent()
        assertEquals(setOf("orx-gui", "orx-parameters", "orx-fx", "orx-noise"), OrxModules.imported(code).map { it.artifact }.toSet())
    }

    fun `test catalog keys`() {
        val toml = """
            [libraries]
            gui = {group = "org.openrndr.extra", name = "orx-gui", version = "0.5.0" }
            mesh-core = {group = "org.openrndr.extra", name = "orx-mesh", version = "0.5.0" }

            [bundles]
            basic = ["camera", "color"]
        """.trimIndent()
        assertEquals(mapOf("orx-gui" to "gui", "orx-mesh" to "mesh-core"), OrxModules.catalogKeys(toml))
    }

    fun `test orx version of the project`() {
        val root = Files.createTempDirectory("orx-version")
        try {
            Files.createDirectories(root.resolve("gradle"))
            val versions = root.resolve("gradle/libs.versions.toml")
            Files.writeString(versions, "[versions]\nopenrndr = \"0.5.0\"\norx = \"0.5.1\"\n")
            assertEquals("0.5.1", OrxModules.projectVersion(root))
            Files.writeString(versions, "[versions]\norx = { require = \"0.6.0-SNAPSHOT\" }\n")
            assertEquals("0.6.0-SNAPSHOT", OrxModules.projectVersion(root))
            Files.delete(versions)
            assertNull(OrxModules.projectVersion(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private val template = """
        dependencies {
            implementation(openrndr.bundles.basic)
        //    implementation(orx.gui)

            implementation(orx.bundles.basic)
            implementation(orx.olive)

            implementation(libs.kotlinx.coroutines.core)
        }
    """.trimIndent()

    fun `test modules go after the last orx line`() {
        assertEquals(
            """
            dependencies {
                implementation(openrndr.bundles.basic)
            //    implementation(orx.gui)

                implementation(orx.bundles.basic)
                implementation(orx.olive)
                implementation(orx.jumpflood)
                implementation(orx.kinect.v1.core)

                implementation(libs.kotlinx.coroutines.core)
            }
            """.trimIndent(),
            OrxBuildFile.withModules(template, listOf("jumpflood", "kinect-v1-core")),
        )
    }

    fun `test a commented out module is uncommented`() {
        assertEquals(
            template.replace("//    implementation(orx.gui)", "    implementation(orx.gui)"),
            OrxBuildFile.withModules(template, listOf("gui")),
        )
    }

    fun `test a module that is already listed is not added again`() {
        assertEquals(template, OrxBuildFile.withModules(template, listOf("olive")))
    }

    fun `test other build file layouts are not edited`() {
        val old = """
            val orxFeatures = setOf<String>(
                "orx-noise",
            )
            dependencies {
                implementation("org.openrndr.extra:orx-gui:0.4.5")
            }
        """.trimIndent()
        assertNull(OrxBuildFile.withModules(old, listOf("gui")))
    }
}
