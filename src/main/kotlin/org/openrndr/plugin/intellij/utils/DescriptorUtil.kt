package org.openrndr.plugin.intellij.utils

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtValueArgument
import org.openrndr.color.Linearity
import org.openrndr.plugin.intellij.editor.ConstantValueContainer

/**
 * Resolution helpers built on top of the Kotlin Analysis API (`analyze {}` / [KaSession]).
 *
 * These replace the old K1 descriptor / `org.jetbrains.kotlin.resolve.BindingContext` based code.
 * Every function here is meant to be called from inside an `analyze {}` block (hence the [KaSession]
 * receiver); none of the [org.jetbrains.kotlin.analysis.api.symbols.KaSymbol]s or
 * [org.jetbrains.kotlin.analysis.api.types.KaType]s they touch are allowed to escape that block.
 */

private val COLOR_MODEL_PACKAGES = setOf(
    "org.openrndr.color",
    "org.openrndr.extra.color.presets",
    "org.openrndr.extra.color.spaces"
)

private val SHORTHAND_FUNCTIONS = setOf("rgb", "hsl", "hsv")

/** The package a callable [symbol] is declared in, or `null` if it is not a color model declaration. */
internal fun colorModelPackageOrNull(symbol: KaCallableSymbol): String? {
    val pkg = when (symbol) {
        is KaConstructorSymbol -> symbol.containingClassId?.packageFqName?.asString()
        else -> symbol.callableId?.packageName?.asString()
    }
    return pkg?.takeIf { it in COLOR_MODEL_PACKAGES }
}

internal fun isColorModelSymbol(symbol: KaCallableSymbol): Boolean =
    colorModelPackageOrNull(symbol) != null

/** True if [type] is a color model type (declared in one of the openrndr color packages). */
internal fun isColorModelType(type: KaType): Boolean {
    val classId = (type as? KaClassType)?.classId ?: return false
    return classId.packageFqName.asString() in COLOR_MODEL_PACKAGES
}

/**
 * The simple name used to choose a [org.openrndr.plugin.intellij.editor.ColorRGBaDescriptor]:
 * the class name for a constructor (e.g. `ColorRGBa`), or the function name otherwise (e.g. `fromHex`).
 */
internal fun callableShortName(symbol: KaFunctionSymbol): String? = when (symbol) {
    is KaConstructorSymbol -> symbol.containingClassId?.shortClassName?.identifier
    is KaNamedFunctionSymbol -> symbol.name.identifier
    else -> null
}

private fun isShorthandFunction(symbol: KaFunctionSymbol): Boolean {
    val callableId = (symbol as? KaNamedFunctionSymbol)?.callableId ?: return false
    return callableId.packageName.asString() == "org.openrndr.color" &&
            callableId.callableName.identifier in SHORTHAND_FUNCTIONS
}

/**
 * Computes argument constants for a resolved color-constructor [call] if it can, returns `null` otherwise.
 *
 * Mirrors the K1 `computeValueArguments`: it produces a complete mapping of all value parameters to
 * their argument values, filling in known defaults for omitted parameters. Unlike the K1 version, the
 * Analysis API [KaFunctionCall.argumentMapping] is keyed by the argument expression and only contains
 * parameters that received an explicit argument, so defaults are reconstructed from the symbol's
 * [KaFunctionSymbol.valueParameters].
 */
internal fun KaSession.computeValueArguments(call: KaFunctionCall<*>): ArgumentMap? {
    val symbol = call.symbol
    val shorthand = isShorthandFunction(symbol)

    // parameter name -> the argument expression that was explicitly passed for it
    val providedByName: Map<String, KtExpression> = buildMap {
        for ((argExpression, parameterSignature) in call.argumentMapping) {
            put(parameterSignature.name.identifier, argExpression)
        }
    }

    return buildMap {
        symbol.valueParameters.forEachIndexed { index, parameter ->
            val name = parameter.name.identifier
            val argExpression = providedByName[name]
            val container: ConstantValueContainer = when {
                argExpression == null -> {
                    if (!parameter.hasDefaultValue) return null
                    ConstantValueContainer.getDefaultValueIfKnown(name, shorthand) ?: return null
                }

                name == "ref" -> resolveWhitePoint(argExpression) ?: return null

                // The linearity enum is the only non-numeric, non-ref parameter of the color models; it is
                // not a color component, so we keep it as an ignored argument.
                name == "linearity" -> resolveLinearity(argExpression)

                // Every other parameter is a numeric (or hex) color component. If we cannot fold it to a
                // compile-time constant, we must fail the whole resolution (returning null), otherwise the
                // remaining components would shift and yield a wrong color.
                else -> {
                    val value = evaluateConstant(argExpression) ?: return null
                    ConstantValueContainer.Constant(value)
                }
            }
            put(ColorArgument(index, name), container)
        }

        // openrndr's double `rgb(...)` shorthand carries no `linearity` parameter, yet its result linearity
        // changed from sRGB to linear in openrndr 0.5.0 (commit 01d4f82). Because we compute the swatch with
        // our *bundled* openrndr — which can't observe the project's behavior — we detect the project's
        // openrndr-color version from the resolved `rgb` symbol and record the matching linearity, which the
        // RGB descriptor then honors instead of calling the bundled `rgb()`.
        if (callableShortName(symbol) == "rgb") {
            val linearity = rgbShorthandLinearity(openrndrColorVersionOf(symbol))
            put(ColorArgument(symbol.valueParameters.size, "linearity"), ConstantValueContainer.LinearityArg(linearity))
        }
    }
}

/**
 * True if [symbol] is a static `ColorRGBa` color we can rewrite as `ColorRGBa.fromHex(...)`.
 *
 * This covers two declaration shapes that both render as `ColorRGBa.NAME`:
 *  - openrndr's built-ins (e.g. `ColorRGBa.RED`), which are members of `ColorRGBa.Companion`;
 *  - orx's presets (e.g. `ColorRGBa.ORANGE_RED`), which are top-level **extension** properties on
 *    `ColorRGBa.Companion` declared in `org.openrndr.extra.color.presets` and so have no enclosing class.
 *
 * Matching on the (`ColorRGBa`) return type recognises both, while the package gate keeps it limited to the
 * openrndr color model — mirroring how [ColorUtil.resolveToColor] decides a symbol is a static color in the first place.
 * Returning the `ColorRGBa` type also naturally excludes other color-model statics like `ColorXYZa` white
 * points, which we cannot express via `fromHex`.
 */
internal fun isColorRGBaStatic(symbol: KaVariableSymbol): Boolean {
    if (!isColorModelSymbol(symbol)) return false
    val classId = (symbol.returnType as? KaClassType)?.classId ?: return false
    return classId.packageFqName.asString() == "org.openrndr.color" &&
            classId.shortClassName.identifier == "ColorRGBa"
}

/**
 * A resolved argument of a color-constructor call, identified by parameter [index] and [name], with the
 * [valueArgument] PSI that was explicitly passed (or `null` if the parameter took its default value).
 *
 * Unlike the [org.jetbrains.kotlin.analysis.api.symbols.KaSymbol]s used to compute it, the
 * [com.intellij.psi.PsiElement] is safe to use outside the `analyze {}` block, so this is what we hand
 * to the color-picker rewrite logic.
 */
internal class ResolvedArgInfo(
    val index: Int,
    val name: String,
    val valueArgument: KtValueArgument?
)

/**
 * Extracts, in parameter order, the [ResolvedArgInfo] for every value parameter of [call]. Used by the
 * color picker to non-destructively rebuild the argument list (preserving named and defaulted arguments).
 */
internal fun KaSession.resolvedColorArguments(call: KaFunctionCall<*>): List<ResolvedArgInfo> {
    val valueArgumentsByName: Map<String, KtValueArgument> = buildMap {
        for ((argExpression, parameterSignature) in call.argumentMapping) {
            (argExpression.parent as? KtValueArgument)?.let { put(parameterSignature.name.identifier, it) }
        }
    }
    return call.symbol.valueParameters.mapIndexed { index, parameter ->
        ResolvedArgInfo(index, parameter.name.identifier, valueArgumentsByName[parameter.name.identifier])
    }
}

/**
 * Folds [expression] to a compile-time constant ([Double]/[Int]/[String]) if possible.
 *
 * [org.jetbrains.kotlin.analysis.api.components.KaEvaluator.evaluate] handles literals and `const val`s,
 * but unlike the K1 `ConstantExpressionEvaluator` it does not fold references to ordinary (non-`const`)
 * `val`s nor simple arithmetic over them. We recover that behaviour here so gutter colors keep working for
 * code like `val a = 0.1; ColorRGBa(0.3, a, 0.2)` and `val b = a + X`.
 */
private fun KaSession.evaluateConstant(expression: KtExpression, depth: Int = 0): Any? {
    expression.evaluate()?.value?.let { return it }
    if (depth > 16) return null
    return when (val expr = KtPsiUtil.deparenthesize(expression)) {
        is KtNameReferenceExpression -> {
            val property = expr.resolveToCall()?.successfulVariableAccessCall()?.symbol?.psi as? KtProperty
            val initializer = property?.initializer ?: return null
            evaluateConstant(initializer, depth + 1)
        }

        is KtPrefixExpression -> {
            val num = evaluateConstant(expr.baseExpression ?: return null, depth + 1) as? Number
            val operand = num?.toDouble() ?: return null
            when (expr.operationToken) {
                KtTokens.MINUS -> -operand
                KtTokens.PLUS -> operand
                else -> null
            }
        }

        is KtBinaryExpression -> {
            val lNum = evaluateConstant(expr.left ?: return null, depth + 1) as? Number
            val rNum = evaluateConstant(expr.right ?: return null, depth + 1) as? Number
            val left = lNum?.toDouble() ?: return null
            val right = rNum?.toDouble() ?: return null
            when (expr.operationToken) {
                KtTokens.PLUS -> left + right
                KtTokens.MINUS -> left - right
                KtTokens.MUL -> left * right
                KtTokens.DIV -> left / right
                else -> null
            }
        }

        else -> null
    }
}

/**
 * Resolves a `linearity` argument expression (e.g. `Linearity.LINEAR`) to its [Linearity] value. Falls back to
 * the constructor default ([ConstantValueContainer.DEFAULT_LINEARITY]) for anything we cannot fold to one of the
 * two known enum entries — including the uncommon case of an indirection like `val l = Linearity.SRGB; ...(l)`.
 */
private fun KaSession.resolveLinearity(expression: KtExpression): ConstantValueContainer.LinearityArg {
    val name = expression.resolveToCall()?.successfulVariableAccessCall()?.symbol
        ?.let { it as? KaNamedSymbol }?.name?.identifier
    val value = when (name) {
        Linearity.SRGB.name -> Linearity.SRGB
        Linearity.LINEAR.name -> Linearity.LINEAR
        else -> ConstantValueContainer.DEFAULT_LINEARITY
    }
    return ConstantValueContainer.LinearityArg(value)
}

private val OPENRNDR_COLOR_JAR = Regex("""openrndr-color(?:-jvm)?-(\d+)\.(\d+)\.\d+""")

/**
 * The `openrndr-color` version, as (major, minor), of the library the resolved [symbol] comes from, or `null`
 * if it can't be read off the classpath. We key off the resolved symbol (rather than one project-wide version)
 * so a call that resolves to an unusual transitive openrndr is still attributed to the version that applies.
 */
internal fun KaSession.openrndrColorVersionOf(symbol: KaSymbol): Pair<Int, Int>? {
    val path = symbol.psi?.containingFile?.virtualFile?.path ?: return null
    return parseOpenrndrColorMajorMinor(path)
}

/** Extracts the (major, minor) of an `openrndr-color` jar from [path], or `null` if it isn't found there. */
internal fun parseOpenrndrColorMajorMinor(path: String): Pair<Int, Int>? =
    OPENRNDR_COLOR_JAR.find(path)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/**
 * The linearity of openrndr's double `rgb(...)` shorthand for the given openrndr-color [version]: sRGB before
 * 0.5.0, linear from 0.5.0 on (openrndr commit 01d4f82, which also added the sRGB `rgb(Int, …)` overload).
 * Falls back to sRGB when the version is unknown — matching openrndr's long-standing behavior and our bundle.
 */
internal fun rgbShorthandLinearity(version: Pair<Int, Int>?): Linearity =
    if (version != null && (version.first > 0 || version.second >= 5)) Linearity.LINEAR else Linearity.SRGB

/** Resolves a `ref` argument expression (a static white point or an explicit `ColorXYZa(...)`). */
private fun KaSession.resolveWhitePoint(expression: KtExpression): ConstantValueContainer.WhitePoint? {
    val callInfo = expression.resolveToCall() ?: return null

    callInfo.successfulVariableAccessCall()?.let { variableAccess ->
        val name = (variableAccess.symbol as? KaNamedSymbol)?.name?.identifier ?: return null
        val whitePoint = ColorUtil.staticWhitePointMap[name] ?: return null
        return ConstantValueContainer.WhitePoint(whitePoint)
    }

    val constructorCall = callInfo.successfulFunctionCallOrNull() ?: return null
    val whitePoint = computeValueArguments(constructorCall)?.computeWhitePoint() ?: return null
    return ConstantValueContainer.WhitePoint(whitePoint)
}
