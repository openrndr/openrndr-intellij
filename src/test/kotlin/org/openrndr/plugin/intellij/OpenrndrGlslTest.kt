package org.openrndr.plugin.intellij

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** The GLSL pieces injected into OPENRNDR's shader strings, as the GLSL plugin would see them. */
class OpenrndrGlslTest : BasePlatformTestCase() {

    /** The injected text for the string containing `<caret>`, or null when nothing is injected. */
    private fun injected(code: String): String? {
        myFixture.configureByText("Sketch.kt", code.trimIndent())
        val host = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), KtStringTemplateExpression::class.java)
            ?: error("no string at the caret")
        val places = OpenrndrGlsl.places(host) ?: return null
        return places.joinToString("") { it.prefix + it.range.substring(host.text) + it.suffix }
    }

    fun `test fragment transform is wrapped in main with OPENRNDR's variables`() {
        val glsl = injected(
            """
            fun main() {
                shadeStyle {
                    fragmentTransform = ""${'"'}
                        x_fill.rgb *= 0.5;<caret>
                    ""${'"'}.trimIndent()
                }
            }
            """
        )!!
        println(glsl)
        assertTrue(glsl, glsl.contains("vec4 x_fill;\n"))
        assertTrue(glsl, glsl.contains("void main() {\n"))
        // The raw string keeps its own indentation and line breaks
        assertTrue(glsl, Regex("""void main\(\) \{\s+x_fill\.rgb \*= 0\.5;\s+}$""").containsMatchIn(glsl))
    }

    fun `test parameters are declared from their values`() {
        val glsl = injected(
            """
            fun main() {
                shadeStyle {
                    parameter("time", 1.0)
                    parameter("offset", Vector2(1.0, 2.0))
                    parameter("tint", ColorRGBa.PINK)
                    parameter("count", 3)
                    parameter("unknown", someValue())
                    fragmentTransform = "x_fill.rgb *= p_time;<caret>"
                }
            }
            """
        )!!
        assertTrue(glsl, glsl.contains("float p_time;"))
        assertTrue(glsl, glsl.contains("vec2 p_offset;"))
        assertTrue(glsl, glsl.contains("vec4 p_tint;"))
        assertTrue(glsl, glsl.contains("int p_count;"))
        assertFalse("no guessed type", glsl.contains("p_unknown"))
    }

    fun `test interpolations become gaps`() {
        val glsl = injected(
            """
            fun main() {
                val amount = 0.5
                shadeStyle {
                    vertexTransform = "x_position.x += ${'$'}amount; x_position.y += ${'$'}{amount * 2.0};<caret>"
                }
            }
            """
        )!!
        assertTrue(glsl, glsl.contains("x_position.x += amount; x_position.y += x_;\n}"))
    }

    fun `test compute transform and qualified assignment`() {
        val glsl = injected(
            """
            fun main() {
                val style = computeStyle { }
                style.computeTransform = "int i = 0;<caret>"
            }
            """
        )!!
        assertTrue(glsl, glsl.endsWith("void main() {\nint i = 0;\n}"))
    }

    fun `test preambles are not wrapped`() {
        val glsl = injected(
            """
            fun main() {
                shadeStyle {
                    fragmentPreamble = "float twice(float x) { return x * 2.0; }<caret>"
                }
            }
            """
        )!!
        assertFalse(glsl, glsl.contains("void main"))
        assertTrue(glsl, glsl.endsWith("float twice(float x) { return x * 2.0; }"))
    }

    fun `test other strings are left alone`() {
        assertNull(injected("fun main() {\n    val fragmentTransform2 = \"x<caret>\"\n    println(\"fragmentTransform\")\n}"))
        assertNull(injected("fun main() {\n    title = \"x_fill<caret>\"\n}"))
    }

    fun `test banner only for files with shader code`() {
        assertTrue(OpenrndrGlsl.hasShaderCode("shadeStyle {\n    fragmentTransform = \"\"\n}"))
        assertTrue(OpenrndrGlsl.hasShaderCode("style.computeTransform += \"…\""))
        assertFalse(OpenrndrGlsl.hasShaderCode("println(\"fragmentTransform\")"))
    }

    fun `test parameter types`() {
        assertEquals("float", OpenrndrGlsl.glslType("0.25"))
        assertEquals("float", OpenrndrGlsl.glslType("seconds"))
        assertEquals("int", OpenrndrGlsl.glslType("-3"))
        assertEquals("bool", OpenrndrGlsl.glslType("true"))
        assertEquals("vec3", OpenrndrGlsl.glslType("Vector3.UNIT_X"))
        assertEquals("ivec2", OpenrndrGlsl.glslType("IntVector2(1, 2)"))
        assertEquals("vec4", OpenrndrGlsl.glslType("rgb(0.2, 0.4, 0.6)"))
        assertEquals("mat4", OpenrndrGlsl.glslType("Matrix44.IDENTITY"))
        assertNull(OpenrndrGlsl.glslType("someValue()"))
    }
}
