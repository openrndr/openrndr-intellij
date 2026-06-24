package org.openrndr.plugin.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import org.intellij.lang.annotations.Language
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.spaces.ColorOKLABa
import org.openrndr.math.Vector2
import java.io.File

/**
 * Base class for the plugin's tests. It boots a light in-memory IntelliJ test fixture whose module has the real
 * openrndr jars (and the Kotlin standard library) on its classpath, so the Kotlin Analysis API resolves
 * `ColorRGBa` and friends exactly as it would in a user's project.
 *
 * The `colorRGBaExpressionTemplate` helpers wrap a snippet of color code in a compilable file (with the openrndr
 * color imports and a `main` function) so individual tests only need to write the expression under test.
 */
abstract class ColorRGBaTestCase : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = PROJECT_DESCRIPTOR

    override fun getTestDataPath(): String = "testData"

    /** Wraps [expression] in a `main` function inside an otherwise empty file with the openrndr imports. */
    @Language("kt")
    protected fun colorRGBaExpressionTemplate(
        @Language("kt", prefix = "fun main() {", suffix = "}") expression: String
    ): String = colorRGBaExpressionTemplate("", expression)

    /** As above, but inserts [prelude] (extra top-level declarations such as `const val`s) before `main`. */
    @Language("kt")
    protected fun colorRGBaExpressionTemplate(
        @Language("kt") prelude: String, @Language("kt", prefix = IMPORTS_PREFIX, suffix = "}") expression: String
    ): String = """
            $IMPORTS
            $prelude

            fun main() {
                $expression
            }
            """.trimIndent()

    companion object {
        /**
         * Light-fixture project model. The openrndr symbols only resolve in this fixture if three things hold,
         * none of which the original fixture did:
         *
         * 1. A **real JDK** (the one running the tests) instead of `IdeaTestUtil.getMockJdk17()`. The mock JDK
         *    has no usable `java.base`, so even `java.lang.String` is unresolvable and every library symbol that
         *    transitively needs the JDK fails. Using the test JVM's own `java.home` also removes the need for an
         *    intellij-community checkout, which previously only existed to supply the mock-JDK roots.
         * 2. The genuine **kotlin-stdlib**. The openrndr classes are Kotlin classes whose symbols reference
         *    stdlib types; `kotlin.Unit` is loaded from the platform's relocated `util-8.jar` at test runtime
         *    (not a stdlib the Kotlin plugin recognises), so we attach the real `kotlin-stdlib.jar` bundled with
         *    the IDE under `plugins/Kotlin/kotlinc/lib`.
         * 3. The libraries attached **inside `configureModule`** (i.e. as part of the project descriptor) rather
         *    than added later in `setUp`. `BasePlatformTestCase` indexes the descriptor's roots and waits for
         *    smart mode *before* the test body runs; a library added in `setUp` instead races an asynchronously
         *    scheduled scan, leaving the project in dumb mode during the test so stub-index lookups (and hence
         *    every gutter/completion resolution) silently return nothing.
         *
         * The plain JVM jars already on the test classpath are used (located via [PathManager.getJarPathForClass])
         * rather than Maven coordinates: resolving `org.openrndr:*-jvm` by coordinate drags in the multiplatform
         * `.knm` metadata variants (via the orx-color POM), which the K2 plugin cannot build symbols from.
         */
        protected val PROJECT_DESCRIPTOR: LightProjectDescriptor =
            object : DefaultLightProjectDescriptor({
                JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
            }) {
                override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                    super.configureModule(module, model, contentEntry)
                    val jarRoots = buildList {
                        add(BUNDLED_KOTLIN_STDLIB)
                        add(jarOf(ColorRGBa::class.java))   // openrndr-color
                        add(jarOf(Vector2::class.java))     // openrndr-math
                        add(jarOf(ColorOKLABa::class.java)) // orx-color
                    }.distinct().map { VfsUtil.getUrlForLibraryRoot(File(it)) }
                    val library = model.moduleLibraryTable.createLibrary("openrndr")
                    library.modifiableModel.apply {
                        jarRoots.forEach { addRoot(it, OrderRootType.CLASSES) }
                        commit()
                    }
                }
            }

        private fun jarOf(clazz: Class<*>): String =
            PathManager.getJarPathForClass(clazz) ?: error("Could not locate the jar for ${clazz.name}")

        /** The `kotlin-stdlib.jar` bundled with the Kotlin plugin inside the IDE under test. */
        private val BUNDLED_KOTLIN_STDLIB: String =
            "${PathManager.getHomePath()}/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"
                .also { check(File(it).exists()) { "Bundled kotlin-stdlib not found at $it" } }

        protected const val IMPORTS: String = """import org.openrndr.color.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.color.spaces.*"""

        protected const val IMPORTS_PREFIX: String = "$IMPORTS\nfun main() {"
    }
}
