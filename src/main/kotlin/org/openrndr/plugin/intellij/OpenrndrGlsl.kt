package org.openrndr.plugin.intellij

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.installAndEnable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.injected.InjectionBackgroundSuppressor
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBlockStringTemplateEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import java.util.function.Function
import javax.swing.JComponent

/**
 * GLSL in OPENRNDR's shader strings: `fragmentTransform`, `vertexTransform`, `geometryTransform` (ShadeStyle) and
 * `computeTransform` (ComputeStyle), plus their preambles. Needs the GLSL Support plugin, which provides the language;
 * without it nothing is injected (and [GlslPluginSuggestion] suggests installing it).
 */
object OpenrndrGlsl {
    const val PLUGIN_ID = "GLSL"
    const val PLUGIN_NAME = "GLSL Support"

    /** Shader code that runs in a function body: wrapped in `void main() { … }` for the GLSL parser. */
    val TRANSFORMS = setOf("fragmentTransform", "vertexTransform", "geometryTransform", "computeTransform")

    /** Shader code at the top level (functions, constants): injected as it is. */
    val PREAMBLES = setOf("fragmentPreamble", "vertexPreamble", "geometryPreamble", "computePreamble")

    /**
     * The variables OPENRNDR declares around a shade style's transforms, so the GLSL plugin knows their types
     * (`x_fill.rgb`); undeclared names are not marked as errors either way.
     */
    private val BUILT_INS = listOf(
        "vec4 x_fill", "vec4 x_stroke", "float x_strokeWeight", "float x_pointSize",
        "vec3 x_position", "vec3 x_normal",
        "mat4 x_modelMatrix", "mat4 x_viewMatrix", "mat4 x_projectionMatrix", "mat4 x_modelNormalMatrix", "mat4 x_viewNormalMatrix",
        "vec3 va_position", "vec3 va_normal", "vec2 va_texCoord0", "vec4 va_color",
        "vec3 v_worldPosition", "vec3 v_viewPosition", "vec4 v_clipPosition", "vec3 v_worldNormal", "vec3 v_viewNormal",
        "vec2 v_texCoord0", "int v_instance",
        "vec3 c_boundsPosition", "vec3 c_boundsSize", "vec2 c_screenPosition", "float c_contourPosition",
        "int c_instance", "int c_element",
        "mat4 u_modelMatrix", "mat4 u_viewMatrix", "mat4 u_projectionMatrix", "mat4 u_modelNormalMatrix", "mat4 u_viewNormalMatrix",
        "vec2 u_viewDimensions", "float u_contentScale", "vec4 u_fill", "vec4 u_stroke", "float u_strokeWeight",
        "vec4 o_color",
    )

    /** One piece of the injected code: [range] of the host string, with text added before and after it. */
    data class Place(val prefix: String, val range: TextRange, val suffix: String)

    /**
     * The GLSL pieces of [host] when it is assigned to one of the shader properties (`fragmentTransform = """…"""`,
     * `style.computeTransform = "…".trimIndent()`), else null. Kotlin's `$name` / `${…}` templates are gaps, filled
     * with the name (or `x_`) so the code around them still parses.
     */
    fun places(host: KtStringTemplateExpression): List<Place>? {
        val property = assignedProperty(host) ?: return null
        val transform = property in TRANSFORMS
        val header = declarations(host).joinToString("") { "$it;\n" } + if (transform) "void main() {\n" else ""
        val footer = if (transform) "\n}" else ""

        val places = ArrayList<Place>()
        var prefix = header
        var start = -1
        var end = -1
        for (entry in host.entries) {
            val range = TextRange.from(entry.startOffsetInParent, entry.textLength)
            if (entry is KtLiteralStringTemplateEntry || entry is KtEscapeStringTemplateEntry) {
                if (start < 0) start = range.startOffset
                end = range.endOffset
                continue
            }
            val gap = when (entry) {
                is KtSimpleNameStringTemplateEntry -> entry.expression?.text ?: "x_"
                is KtBlockStringTemplateEntry -> (entry.expression as? KtNameReferenceExpression)?.text ?: "x_"
                else -> "x_"
            }
            // A literal run ends at an interpolation: the gap becomes the run's suffix
            if (start >= 0) {
                places += Place(prefix, TextRange(start, end), gap)
                prefix = ""
            } else {
                prefix += gap
            }
            start = -1
        }
        if (start >= 0) {
            places += Place(prefix, TextRange(start, end), footer)
        } else {
            // Empty string, or one ending in an interpolation: an empty place before the closing quotes
            val close = host.textLength - (host.lastChild?.textLength ?: 0)
            places += Place(prefix, TextRange(close, close), footer)
        }
        return places
    }

    /** The shader property [host] is assigned to: `fragmentTransform = host`, `x.vertexTransform += host.trimIndent()`. */
    private fun assignedProperty(host: KtStringTemplateExpression): String? {
        // `"""…""".trimIndent()` / `.trimMargin()`
        val value = (host.parent as? KtDotQualifiedExpression)?.takeIf { it.receiverExpression == host } ?: host
        val assignment = value.parent as? KtBinaryExpression ?: return null
        if (assignment.right != value || assignment.operationToken !in ASSIGNMENTS) return null
        val name = when (val left = assignment.left) {
            is KtNameReferenceExpression -> left.getReferencedName()
            is KtDotQualifiedExpression -> (left.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            else -> null
        }
        return name?.takeIf { it in TRANSFORMS || it in PREAMBLES }
    }

    private val ASSIGNMENTS = setOf(KtTokens.EQ, KtTokens.PLUSEQ)

    /**
     * OPENRNDR's variables, and `p_<name>` for each `parameter("name", value)` in the same block whose type shows in
     * the value's text (`1.0`, `Vector2(…)`, `ColorRGBa.RED`, …).
     */
    private fun declarations(host: KtStringTemplateExpression): List<String> {
        val block = host.getStrictParentOfType<KtBlockExpression>() ?: return BUILT_INS
        val parameters = block.statements.mapNotNull { statement ->
            val call = (statement as? KtCallExpression) ?: (statement as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression
            if ((call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "parameter") return@mapNotNull null
            val arguments = call.valueArguments
            val name = (arguments.getOrNull(0)?.getArgumentExpression() as? KtStringTemplateExpression)
                ?.takeIf { !it.hasInterpolation() }?.entries?.joinToString("") { it.text } ?: return@mapNotNull null
            val type = arguments.getOrNull(1)?.getArgumentExpression()?.text?.let(::glslType) ?: return@mapNotNull null
            "$type p_$name"
        }
        return BUILT_INS + parameters
    }

    /** The GLSL type of a parameter value from its Kotlin text, or null when it does not show. */
    fun glslType(value: String): String? {
        val text = value.trim()
        return when {
            text == "true" || text == "false" -> "bool"
            Regex("""-?\d+""").matches(text) -> "int"
            Regex("""-?(\d+\.\d*|\.\d+)(e-?\d+)?""").matches(text) || text == "seconds" || text.endsWith(".toDouble()") -> "float"
            text.startsWith("IntVector2") -> "ivec2"
            text.startsWith("IntVector3") -> "ivec3"
            text.startsWith("Vector2") -> "vec2"
            text.startsWith("Vector3") -> "vec3"
            text.startsWith("Vector4") -> "vec4"
            text.startsWith("ColorRGBa") || Regex("""^(rgb|rgba|hsv|hsl)\(""").containsMatchIn(text) -> "vec4"
            text.startsWith("Matrix44") -> "mat4"
            text.startsWith("Matrix33") -> "mat3"
            else -> null
        }
    }

    /** Whether the GLSL Support plugin is installed (enabled or not). */
    fun isPluginInstalled() = PluginManagerCore.isPluginInstalled(PluginId.getId(PLUGIN_ID))

    private val SHADER_PROPERTY = Regex("""\b(${(TRANSFORMS + PREAMBLES).joinToString("|")})\s*\+?=""")

    /** Whether [text] assigns one of the shader properties. */
    fun hasShaderCode(text: CharSequence) = SHADER_PROPERTY.containsMatchIn(text)
}

/** Injects GLSL (from the GLSL Support plugin) into OPENRNDR's shader strings; see [OpenrndrGlsl]. */
class OpenrndrGlslInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(KtStringTemplateExpression::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? KtStringTemplateExpression ?: return
        val language = Language.findLanguageByID(OpenrndrGlsl.PLUGIN_ID) ?: return
        if (!host.isValidHost || !Openrndr.isProject(host.project)) return
        val places = OpenrndrGlsl.places(host) ?: return
        // Keep the editor's background: the GLSL highlighting is enough to show which strings are shaders
        host.putUserData(InjectionBackgroundSuppressor.SUPPRESS_INJECTION_BACKGROUND, Unit)
        registrar.startInjecting(language)
        for (place in places) registrar.addPlace(place.prefix, place.suffix, host, place.range)
        registrar.doneInjecting()
    }
}

/**
 * A banner above OPENRNDR files with shader code (`fragmentTransform = …`) while GLSL Support is not installed:
 * Install (the IDE's own plugin installer) or Dismiss (for good).
 */
class GlslPluginSuggestion : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "kt" || PropertiesComponent.getInstance().isTrueValue(DISMISSED)) return null
        if (OpenrndrGlsl.isPluginInstalled() || !Openrndr.isProject(project)) return null
        val content = FileDocumentManager.getInstance().getDocument(file)?.immutableCharSequence ?: return null
        if (!OpenrndrGlsl.hasShaderCode(content)) return null
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = "Install ${OpenrndrGlsl.PLUGIN_NAME} to highlight the GLSL in shade and compute styles"
                createActionLabel("Install ${OpenrndrGlsl.PLUGIN_NAME}") {
                    installAndEnable(project, setOf(PluginId.getId(OpenrndrGlsl.PLUGIN_ID)), true, false) {
                        EditorNotifications.getInstance(project).updateAllNotifications()
                    }
                }
                createActionLabel("Dismiss") {
                    PropertiesComponent.getInstance().setValue(DISMISSED, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    private companion object {
        const val DISMISSED = "openrndr.glslSuggestion.dismissed"
    }
}
