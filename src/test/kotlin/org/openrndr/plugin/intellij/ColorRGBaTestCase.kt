package org.openrndr.plugin.intellij

import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import org.intellij.lang.annotations.Language

abstract class ColorRGBaTestCase : BasePlatformTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = PROJECT_DESCRIPTOR

    override fun getTestDataPath(): String = "testData"

    @Language("kt")
    protected fun colorRGBaExpressionTemplate(
        @Language("kt", prefix = "fun main() {", suffix = "}") expression: String
    ): String = colorRGBaExpressionTemplate("", expression)

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
        protected val PROJECT_DESCRIPTOR = DefaultLightProjectDescriptor(
            { IdeaTestUtil.getMockJdk17() }, listOf(
                "org.openrndr:openrndr-color-jvm:${System.getProperty("version_used_for.openrndr")}",
                "org.openrndr.extra:orx-color-jvm:${System.getProperty("version_used_for.orx")}",
            )
        )

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