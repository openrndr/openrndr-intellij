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
import org.openrndr.plugin.intellij.editor.ConstantValueContainer

/**
 * Resolution helpers built on top of the Kotlin Analysis API (`analyze {}` / [KaSession]).
 *
 * These replace the old K1 descriptor / [org.jetbrains.kotlin.resolve.BindingContext] based code.
 * Every function here is meant to be called from inside an `analyze {}` block (hence the [KaSession]
 * receiver); none of the [org.jetbrains.kotlin.analysis.api.symbols.KaSymbol]s or
 * [org.jetbrains.kotlin.analysis.api.types.KaType]s they touch are allowed to escape that block.
 */

private val COLOR_MODEL_PACKAGES = setOf(
    "org.openrndr.color", "org.openrndr.extra.color.presets", "org.openrndr.extra.color.spaces"
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

internal fun isColorModelSymbol(symbol: KaCallableSymbol): Boolean = colorModelPackageOrNull(symbol) != null

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
                name == "linearity" -> ConstantValueContainer.Other

                // Every other parameter is a numeric (or hex) color component. If we cannot fold it to a
                // compile-time constant we must fail the whole resolution (returning null), otherwise the
                // remaining components would shift and yield a wrong color.
                else -> {
                    val value = evaluateConstant(argExpression) ?: return null
                    ConstantValueContainer.Constant(value)
                }
            }
            put(ColorArgument(index, name), container)
        }
    }
}

/** True if [symbol] is one of the static colors declared on `ColorRGBa` (e.g. `ColorRGBa.RED`). */
internal fun isColorRGBaStatic(symbol: KaVariableSymbol): Boolean {
    val classId = symbol.callableId?.classId ?: return false
    return classId.outermostClassId.shortClassName.identifier == "ColorRGBa"
}

/**
 * A resolved argument of a color-constructor call, identified by parameter [index] and [name], with the
 * [valueArgument] PSI that was explicitly passed (or `null` if the parameter took its default value).
 *
 * Unlike the [org.jetbrains.kotlin.analysis.api.symbols.KaSymbol]s used to compute it, the
 * [com.intellij.psi.PsiElement] is safe to use outside the `analyze {}` block, so this is what we hand
 * to the color-picker rewrite logic.
 */
internal class ResolvedArgInfo(val index: Int, val name: String, val valueArgument: KtValueArgument?)

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
            val initializer = (expr.resolveToCall()?.successfulVariableAccessCall()?.symbol?.psi as? KtProperty)
                ?.initializer ?: return null
            evaluateConstant(initializer, depth + 1)
        }

        is KtPrefixExpression -> {
            val operand = (evaluateConstant(expr.baseExpression ?: return null, depth + 1) as? Number)?.toDouble()
                ?: return null
            when (expr.operationToken) {
                KtTokens.MINUS -> -operand
                KtTokens.PLUS -> operand
                else -> null
            }
        }

        is KtBinaryExpression -> {
            val left = (evaluateConstant(expr.left ?: return null, depth + 1) as? Number)?.toDouble() ?: return null
            val right = (evaluateConstant(expr.right ?: return null, depth + 1) as? Number)?.toDouble() ?: return null
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
