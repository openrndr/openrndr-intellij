package org.openrndr.plugin.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.awt.Color
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.math.RoundingMode

/**
 * Gutter color swatches and the color picker for OPENRNDR colors in Kotlin: `ColorRGBa(…)`, `rgb(…)`,
 * `ColorRGBa.fromHex(…)`, `hsv(…)`, `hsl(…)`, named colors like `ColorRGBa.RED` and orx presets (picker), and
 * every other OPENRNDR/orx color model (swatch only).
 *
 * - Expressions are recognised with the Kotlin Analysis API, only ever in the background (the gutter pass).
 * - Colors are computed by [OpenrndrColorRuntime] with the project's own OPENRNDR classes.
 * - What a picked color should be written as ([Edit]) is decided together with the swatch, so picking a color
 *   only edits text: no analysis on the UI thread, and a whole drag in the picker is one undo step.
 */
class OpenrndrColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? {
        val expression = candidate(element) ?: return null
        val project = expression.project
        if (!Openrndr.isProject(project)) return null

        // A color only depends on the expression's text, the file's imports and the project's libraries, so it is
        // cached by those: after typing, the gutter is refreshed without analysing every color again. (Clicking a
        // swatch the gutter has not refreshed yet makes the platform fail on the replaced element.)
        val key = cacheKey(expression) ?: return null
        val cache = swatchCache(project)
        cache[key]?.let { cached ->
            element.putUserData(EDIT, cached.swatch?.edit)
            return cached.swatch?.color
        }
        // Analysis is for the background gutter pass only, and needs the indexes
        if (ApplicationManager.getApplication().isDispatchThread || DumbService.isDumb(project)) return null

        val swatch = compute(expression)
        if (cache.size > MAX_CACHED) cache.clear()
        cache[key] = Cached(swatch)
        element.putUserData(EDIT, swatch?.edit)
        return swatch?.color
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (!element.isValid) return
        val expression = candidate(element) ?: return
        val edit = element.getUserData(EDIT) ?: return
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return
        val project = expression.project
        val document = PsiDocumentManager.getInstance(project).getDocument(expression.containingFile) ?: return
        val next = arrayOfNulls<Edit>(1)
        // The platform calls this inside a write action but not a command; grouping by document merges a whole
        // drag in the picker into one undo step
        CommandProcessor.getInstance().executeCommand(project, {
            next[0] = apply(edit, expression, color, OpenrndrColorRuntime.getInstance(project), module)
        }, "Change Color", document, document)
        // The same swatch keeps getting picker updates; remember how the rewritten code is edited next time
        next[0]?.let { element.putUserData(EDIT, it) }
    }

    /**
     * The color of [expression] if it is a whole color expression (`rgb(…)`, `ColorRGBa.RED`, …), for completion
     * swatches. Uses the gutter's cache; computing needs a background thread.
     */
    fun colorOf(expression: KtExpression): Color? {
        val anchor = PsiTreeUtil.getDeepestFirst(expression)
        return if (candidate(anchor) == expression) getColorFrom(anchor) else null
    }

    // ---- Recognising color expressions ----------------------------------------------------------------------

    /**
     * The color expression [element] is the swatch anchor of: the callee of `Name(…)` / `rgb(…)`, or the receiver
     * of `ColorRGBa.RED` / `ColorRGBa.fromHex(…)`. One anchor per expression, so each color gets one swatch.
     * Expressions that are only the start of a chain (`ColorRGBa.RED.shade(0.5)`) get none, since that would
     * show the wrong color.
     */
    private fun candidate(element: PsiElement): KtExpression? {
        if (element !is LeafPsiElement || element.elementType != KtTokens.IDENTIFIER || !element.isValid) return null
        val reference = element.parent as? KtNameReferenceExpression ?: return null
        if (PsiTreeUtil.getParentOfType(reference, KtImportDirective::class.java) != null) return null
        val name = reference.getReferencedName()
        val parent = reference.parent

        val expression: KtExpression = when {
            // Name(…), unless it is the selector of Receiver.name(…)
            parent is KtCallExpression && parent.calleeExpression == reference -> {
                val qualified = parent.parent as? KtDotQualifiedExpression
                if (qualified != null && qualified.selectorExpression == parent) return null
                if (!name.startsWith("Color") && name !in SHORTHANDS) return null
                parent
            }
            // Receiver.NAME or Receiver.name(…)
            parent is KtDotQualifiedExpression && parent.receiverExpression == reference -> {
                if (!name.startsWith("Color")) return null
                parent
            }
            else -> return null
        }
        val outer = expression.parent as? KtDotQualifiedExpression
        if (outer != null && outer.receiverExpression == expression) return null
        return expression
    }

    private fun compute(expression: KtExpression): Swatch? {
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return null
        val runtime = OpenrndrColorRuntime.getInstance(expression.project)
        val described = analyze(expression) { describe(expression) } ?: return null
        val value = runtime.evaluate(module, described.call) ?: return null
        val color = runtime.toAwt(module, value) ?: return null
        val edit = when (val edit = described.edit) {
            // RGB components are written in the linearity the expression produces
            is Edit.Components -> if (edit.model == "rgb") edit.copy(linearity = runtime.linearityOf(module, value)) else edit
            else -> edit
        }
        return Swatch(color, edit)
    }

    private class Described(val call: ColorCall, val edit: Edit?)

    private fun KaSession.describe(expression: KtExpression): Described? {
        val info = expression.resolveToCall() ?: return null

        info.successfulVariableAccessCall()?.let { access ->
            val property = access.symbol as? KaPropertySymbol ?: return null
            val call = propertyCall(property) ?: return null
            // `ColorRGBa.NAME` can be rewritten as `ColorRGBa.fromHex(…)`
            val isRgbaConstant = (property.returnType as? KaClassType)?.classId?.asFqNameString() == OpenrndrColorEvaluator.RGBA &&
                ((expression as? KtDotQualifiedExpression)?.receiverExpression as? KtNameReferenceExpression)?.getReferencedName() == "ColorRGBa"
            return Described(call, if (isRgbaConstant) Edit.Constant else null)
        }

        val functionCall = info.successfulFunctionCallOrNull() ?: return null
        val symbol = functionCall.symbol
        if (symbol.valueParameters.any { it.isVararg }) return null
        val provided = functionCall.valueArgumentMapping.entries.associate { (argument, signature) -> signature.symbol.name.asString() to argument }

        val types = symbol.valueParameters.map { jvmType(it.returnType) ?: return null }
        val args = symbol.valueParameters.map { parameter ->
            val argument = provided[parameter.name.asString()]
            when {
                argument != null -> toArg(argument) ?: return null
                parameter.hasDefaultValue -> null
                else -> return null
            }
        }

        val call: ColorCall = when (symbol) {
            is KaConstructorSymbol -> {
                val owner = symbol.containingClassId ?: return null
                ColorCall.Construct(binaryName(owner), types, args)
            }
            is KaNamedFunctionSymbol -> {
                val callableId = symbol.callableId ?: return null
                val pkg = callableId.packageName.asString()
                if (!isColorPackage(pkg)) return null
                val classId = callableId.classId
                if (classId != null) {
                    // Member of an object or companion (e.g. ColorRGBa.fromHex)
                    val receiver = objectInstance(classId) ?: return null
                    ColorCall.Invoke(pkg, binaryName(classId), callableId.callableName.asString(), receiver, types, args)
                } else {
                    // Top-level function, possibly an extension on a companion
                    val receiverType = symbol.receiverParameter?.returnType
                    if (receiverType == null) {
                        ColorCall.Invoke(pkg, null, callableId.callableName.asString(), null, types, args)
                    } else {
                        val receiverClass = (receiverType as? KaClassType)?.classId ?: return null
                        val receiver = objectInstance(receiverClass) ?: return null
                        val receiverJvm = jvmType(receiverType) ?: return null
                        ColorCall.Invoke(pkg, null, callableId.callableName.asString(), null, listOf(receiverJvm) + types, listOf(receiver) + args)
                    }
                }
            }
            else -> return null
        }
        return Described(call, editFor(symbol, types))
    }

    /** The getter call for a color constant: `ColorRGBa.RED`, an orx preset, or a white point like `ColorXYZa.NEUTRAL`. */
    private fun KaSession.propertyCall(property: KaPropertySymbol): ColorCall? {
        val callableId = property.callableId ?: return null
        val pkg = callableId.packageName.asString()
        if (!isColorPackage(pkg)) return null
        // Kotlin's JVM getter name: RED -> getRED, ORANGE_RED -> getORANGE_RED
        val getter = "get" + callableId.callableName.asString().replaceFirstChar { it.uppercaseChar() }
        val classId = callableId.classId
        if (classId != null) {
            val receiver = objectInstance(classId) ?: return null
            return ColorCall.Invoke(pkg, binaryName(classId), getter, receiver, emptyList(), emptyList())
        }
        // Extension property on a companion (orx presets: `val ColorRGBa.Companion.ORANGE_RED`)
        val receiverType = property.receiverParameter?.returnType ?: return null
        val receiverClass = (receiverType as? KaClassType)?.classId ?: return null
        val receiver = objectInstance(receiverClass) ?: return null
        return ColorCall.Invoke(pkg, null, getter, null, listOf(jvmType(receiverType) ?: return null), listOf(receiver))
    }

    /** A constant, an enum entry (`Linearity.SRGB`) or a color constant (`ColorXYZa.NEUTRAL`) passed as an argument. */
    private fun KaSession.toArg(argument: KtExpression): ColorArg? {
        argument.evaluate()?.value?.let { return ColorArg.Const(it) }
        val access = argument.resolveToCall()?.successfulVariableAccessCall() ?: return null
        return when (val symbol = access.symbol) {
            is KaEnumEntrySymbol -> {
                val enumClass = symbol.callableId?.classId ?: return null
                ColorArg.EnumEntry(binaryName(enumClass), symbol.name.asString())
            }
            is KaPropertySymbol -> propertyCall(symbol)?.let { ColorArg.Value(it) }
            else -> null
        }
    }

    /** The static field holding an `object` or companion instance. */
    private fun KaSession.objectInstance(classId: ClassId): ColorArg? {
        val cls = findClass(classId) ?: return null
        return when (cls.classKind) {
            KaClassKind.COMPANION_OBJECT -> {
                val outer = classId.outerClassId ?: return null
                ColorArg.StaticField(binaryName(outer), classId.shortClassName.asString())
            }
            KaClassKind.OBJECT -> ColorArg.StaticField(binaryName(classId), "INSTANCE")
            else -> null
        }
    }

    private fun KaSession.jvmType(type: KaType): String? {
        val classId = (type as? KaClassType)?.classId ?: return null
        val nullable = type.isMarkedNullable
        return when (classId.asFqNameString()) {
            "kotlin.Double" -> if (nullable) "java.lang.Double" else "double"
            "kotlin.Float" -> if (nullable) "java.lang.Float" else "float"
            "kotlin.Int" -> if (nullable) "java.lang.Integer" else "int"
            "kotlin.Long" -> if (nullable) "java.lang.Long" else "long"
            "kotlin.Boolean" -> if (nullable) "java.lang.Boolean" else "boolean"
            "kotlin.String" -> "java.lang.String"
            "kotlin.Any" -> "java.lang.Object"
            else -> binaryName(classId)
        }
    }

    /** How a picked color is written back for this call, or null when it is shown but not editable. */
    private fun editFor(symbol: KaFunctionSymbol, types: List<String>): Edit? {
        val name = when (symbol) {
            is KaConstructorSymbol -> symbol.containingClassId?.asFqNameString()
            is KaNamedFunctionSymbol -> symbol.callableId?.let { "${it.packageName}.${it.callableName}" }
            else -> null
        }
        val alpha = symbol.valueParameters.getOrNull(3)?.name?.asString()
        return when (name) {
            OpenrndrColorEvaluator.RGBA, "org.openrndr.color.rgba" -> Edit.Components("rgb", null, alpha)
            "org.openrndr.color.rgb" -> when (types) {
                listOf("java.lang.String") -> Edit.Hex(asString = true)
                listOf("int", "int", "int", "int") -> Edit.Components("rgb", "SRGB", alpha, ints = true, positional = true)
                listOf("double", "double") -> Edit.Components("rgb", null, null, positional = true)
                else -> Edit.Components("rgb", null, alpha)
            }
            "org.openrndr.color.ColorRGBa.Companion.fromHex" -> Edit.Hex(asString = types == listOf("java.lang.String"))
            "org.openrndr.color.ColorHSVa", "org.openrndr.color.hsv", "org.openrndr.color.hsva" -> Edit.Components("hsv", null, alpha)
            "org.openrndr.color.ColorHSLa", "org.openrndr.color.hsl", "org.openrndr.color.hsla" -> Edit.Components("hsl", null, alpha)
            else -> null
        }
    }

    // ---- Writing a picked color back -----------------------------------------------------------------------

    /** How a picked color is written into the code. */
    private sealed interface Edit {
        /**
         * Rewrite the first four arguments as components of [model] (`rgb` in [linearity], `hsv` or `hsl`).
         * [alphaParameter] names the 4th parameter, to add it when the picked color is transparent.
         * [positional] rewrites the whole list, for overloads with a different shape (`rgb(Int…)`, `rgb(grey, alpha)`).
         */
        data class Components(
            val model: String, val linearity: String?, val alphaParameter: String?,
            val ints: Boolean = false, val positional: Boolean = false,
        ) : Edit

        /** `fromHex("#rrggbb")` / `rgb("#rrggbb")` when [asString], else `fromHex(0xrrggbb)`. */
        data class Hex(val asString: Boolean) : Edit

        /** `ColorRGBa.RED` becomes `ColorRGBa.fromHex("#rrggbb")`. */
        data object Constant : Edit
    }

    private data class Swatch(val color: Color, val edit: Edit?)

    /** A cached result; [swatch] is null for expressions that turned out not to be colors. */
    private class Cached(val swatch: Swatch?)

    /** Swatches by [cacheKey], for the whole project; dropped when its libraries change. */
    private fun swatchCache(project: Project): MutableMap<String, Cached> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, Cached>(), ProjectRootManager.getInstance(project))
        }

    private fun cacheKey(expression: KtExpression): String? {
        val file = expression.containingKtFile
        val module = ModuleUtilCore.findModuleForPsiElement(expression) ?: return null
        return module.name + "\u0000" + file.packageFqName.asString() + "\u0000" + file.importList?.text.orEmpty() + "\u0000" + expression.text
    }

    /** Applies [edit] for [color]; returns how the rewritten code is edited the next time. */
    private fun apply(edit: Edit, expression: KtExpression, color: Color, runtime: OpenrndrColorRuntime, module: Module): Edit? {
        val factory = KtPsiFactory(expression.project)
        val call = (expression as? KtCallExpression)
            ?: ((expression as? KtDotQualifiedExpression)?.selectorExpression as? KtCallExpression)

        when (edit) {
            Edit.Constant -> {
                // Only the `RED` selector is replaced, so the swatch's anchor (`ColorRGBa`) stays valid while dragging
                val selector = (expression as? KtDotQualifiedExpression)?.selectorExpression ?: return null
                selector.replace(factory.createExpression("fromHex(\"${hex(color)}\")"))
                return Edit.Hex(asString = true)
            }

            is Edit.Hex -> {
                val argument = call?.valueArguments?.firstOrNull()?.getArgumentExpression() ?: return null
                val original = (argument as? KtStringTemplateExpression)?.text.orEmpty()
                val text = if (edit.asString) {
                    val value = hex(color, upper = original.any { it in 'A'..'F' })
                    "\"${if (original.isEmpty() || original.contains('#')) value else value.removePrefix("#")}\""
                } else {
                    "0x" + hex(color).removePrefix("#")
                }
                argument.replace(factory.createExpression(text))
                return edit
            }

            is Edit.Components -> {
                val list = call?.valueArgumentList ?: return null
                val values = runtime.components(module, color, edit.model, edit.linearity) ?: return null
                val transparent = color.alpha < 255
                val parts: List<String> = if (edit.positional || edit.ints) {
                    val components = if (edit.ints) {
                        listOf(color.red, color.green, color.blue, color.alpha).map { it.toString() }
                    } else {
                        values.map(::format)
                    }
                    if (transparent) components else components.take(3)
                } else {
                    // Keep every argument; replace the first four (by position or by name) with the new components
                    val parameterOrder = listOf(0, 1, 2, 3)
                    var positionalIndex = 0
                    val kept = list.arguments.map { argument ->
                        val named = argument.getArgumentName()?.asName?.asString()
                        val index = if (named == null) positionalIndex++ else NAMED_COMPONENTS[edit.model]?.indexOf(named)?.takeIf { it >= 0 }
                            ?: (if (named == edit.alphaParameter) 3 else -1)
                        val expressionText = if (index in parameterOrder) format(values[index]) else argument.getArgumentExpression()?.text.orEmpty()
                        (named?.let { "$it = " } ?: "") + expressionText
                    }
                    val hasAlpha = positionalIndex > 3 || list.arguments.any { it.getArgumentName()?.asName?.asString() == edit.alphaParameter }
                    if (transparent && !hasAlpha && edit.alphaParameter != null) kept + "${edit.alphaParameter} = ${format(values[3])}" else kept
                }
                list.replace(factory.createCallArguments("(" + parts.joinToString(", ") + ")"))
                return edit
            }
        }
    }

    private companion object {
        /** Bounds the swatch cache; it is simply cleared when full. */
        const val MAX_CACHED = 5000
        val EDIT = Key.create<Edit>("openrndr.colorEdit")

        /** Top-level OPENRNDR color functions recognised by name before any analysis. */
        val SHORTHANDS = setOf("rgb", "rgba", "hsv", "hsva", "hsl", "hsla")

        /** Parameter names of the components, for named arguments. */
        val NAMED_COMPONENTS = mapOf(
            "rgb" to listOf("r", "g", "b", "alpha"),
            "hsv" to listOf("h", "s", "v", "alpha"),
            "hsl" to listOf("h", "s", "l", "alpha"),
        )

        fun isColorPackage(pkg: String) = OpenrndrColorEvaluator.COLOR_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }

        fun binaryName(classId: ClassId): String {
            val pkg = classId.packageFqName.asString()
            val relative = classId.relativeClassName.asString().replace('.', '$')
            return if (pkg.isEmpty()) relative else "$pkg.$relative"
        }

        fun hex(color: Color, upper: Boolean = false): String {
            val text = "#%02x%02x%02x".format(color.red, color.green, color.blue)
            return if (upper) text.uppercase() else text
        }

        /** Up to three decimals, always with a decimal point (`0.5`, `1.0`, `210.0`). */
        fun format(value: Double): String {
            val text = BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            val clean = if (text == "-0") "0" else text
            return if ('.' in clean) clean else "$clean.0"
        }
    }
}
