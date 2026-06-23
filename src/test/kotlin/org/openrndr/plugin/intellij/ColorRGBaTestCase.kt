package org.openrndr.plugin.intellij

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import org.intellij.lang.annotations.Language
import org.openrndr.color.ColorRGBa
import org.openrndr.extra.color.spaces.ColorOKLABa
import org.openrndr.math.Vector2
import java.io.File

/**
 * Base class for the plugin's tests. It boots a light in-memory IntelliJ test fixture with the real openrndr
 * jars on its classpath (see [PROJECT_DESCRIPTOR]), so the Kotlin Analysis API can resolve `ColorRGBa` and
 * friends exactly as it would in a user's project.
 *
 * The `colorRGBaExpressionTemplate` helpers wrap a snippet of color code in a compilable file (with the
 * openrndr color imports and a `main` function) so individual tests only need to write the expression under test.
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
         * Adds the openrndr libraries as a project library using the actual JVM jars already present on the
         * test runtime classpath (located via [PathManager.getJarPathForClass]). This is more reliable than
         * resolving Maven coordinates at test time: it avoids intermittent "No roots" resolution failures and
         * keeps the multiplatform metadata (`.knm`) variants of these libraries off the classpath, which the
         * K2 Kotlin plugin cannot resolve symbols from.
         */
        protected val PROJECT_DESCRIPTOR: LightProjectDescriptor =
            object : DefaultLightProjectDescriptor({ IdeaTestUtil.getMockJdk17() }, emptyList()) {
                override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                    super.configureModule(module, model, contentEntry)
                    val jarRoots = listOf(
                        ColorRGBa::class.java,    // openrndr-color
                        Vector2::class.java,      // openrndr-math
                        ColorOKLABa::class.java,  // orx-color
                        Unit::class.java,         // kotlin-stdlib
                    ).mapNotNull { PathManager.getJarPathForClass(it) }
                        .distinct()
                        .map { VfsUtil.getUrlForLibraryRoot(File(it)) }
                    val library = model.moduleLibraryTable.createLibrary("openrndr")
                    library.modifiableModel.apply {
                        jarRoots.forEach { addRoot(it, OrderRootType.CLASSES) }
                        commit()
                    }
                }
            }

        @Suppress("JVM_STATIC_ON_CONST_OR_JVM_FIELD")
        @JvmStatic
        protected const val IMPORTS: String = """import org.openrndr.color.*
import org.openrndr.extra.color.presets.*
import org.openrndr.extra.color.spaces.*"""

        @Suppress("JVM_STATIC_ON_CONST_OR_JVM_FIELD")
        @JvmStatic
        protected const val IMPORTS_PREFIX: String = "$IMPORTS\nfun main() {"
    }
}