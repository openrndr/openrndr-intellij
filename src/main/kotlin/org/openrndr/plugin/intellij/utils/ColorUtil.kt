package org.openrndr.plugin.intellij.utils

import com.intellij.patterns.PlatformPatterns.*
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.openrndr.color.ColorModel
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorXYZa
import org.openrndr.color.Linearity
import org.openrndr.plugin.intellij.editor.ColorRGBaDescriptor
import java.awt.Color
import kotlin.reflect.full.memberProperties


@Suppress("UseJBColor")
internal object ColorUtil {
    val colorRGBaFieldNames = arrayOf("r", "g", "b", "alpha")
    val defaultColorRGBa = ColorRGBa(1.0, 1.0, 1.0, 1.0, Linearity.LINEAR)

    fun ColorModel<*>.toAWTColor(): Color = toRGBa().run {
        Color(
            r.toFloat().coerceIn(0f, 1f),
            g.toFloat().coerceIn(0f, 1f),
            b.toFloat().coerceIn(0f, 1f),
            alpha.toFloat().coerceIn(0f, 1f)
        )
    }

    fun Color.toColorRGBa(linearity: Linearity = Linearity.LINEAR) = getComponents(null).let { (r, g, b, a) ->
        ColorRGBa(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble(), linearity)
    }

    /**
     * Uses a combination of Kotlin and Java reflection to create a String-to-Color mapping
     * of all static colors in openrndr.
     */
    val staticColorMap: Map<String, Color> = buildMap {
        // Standard ColorRGBa static colors
        for (property in ColorRGBa.Companion::class.memberProperties) {
            this[property.name] = (property.getter.call(ColorRGBa.Companion) as ColorRGBa).toAWTColor()
        }
        // ColorXYZa static white points
        for (property in ColorXYZa.Companion::class.memberProperties) {
            this[property.name] = (property.getter.call(ColorXYZa.Companion) as ColorXYZa).toAWTColor()
        }
        // There's no easy way to get the ColorRGBa extension properties in orx, we have to use Java reflection
        val extensionColorsJavaClass = Class.forName("org.openrndr.extra.color.presets.ColorsKt")
        for (method in extensionColorsJavaClass.declaredMethods) {
            this[method.name.removePrefix("get")] =
                (method.invoke(ColorRGBa::javaClass, ColorRGBa.Companion) as ColorRGBa).toAWTColor()
        }
    }

    val staticWhitePointMap: Map<String, ColorXYZa> = buildMap {
        // ColorXYZa static white points
        for (property in ColorXYZa.Companion::class.memberProperties) {
            this[property.name] = property.getter.call(ColorXYZa.Companion) as ColorXYZa
        }
    }

    fun PsiElement.resolveToColor(): Color? {
        if (this !is LeafPsiElement) return null
        if (!COLOR_PROVIDER_PATTERN.accepts(this)) return null
        val outerExpression = getParentOfTypes2<KtCallExpression, KtDotQualifiedExpression>() as? KtExpression
            ?: return null
        return analyze(outerExpression) {
            val callInfo = outerExpression.resolveToCall() ?: return@analyze null

            // Static color, e.g. `ColorRGBa.RED`, resolved as a variable (property) access.
            callInfo.successfulVariableAccessCall()?.let { variableAccess ->
                val symbol = variableAccess.symbol
                if (!isColorModelSymbol(symbol)) return@analyze null
                return@analyze staticColorMap[symbol.name.identifier]
            }

            // Function or constructor call, e.g. `ColorRGBa(...)`, `rgb(...)`, `ColorRGBa.fromHex(...)`.
            val functionCall = callInfo.successfulFunctionCallOrNull() ?: return@analyze null
            val symbol = functionCall.symbol
            if (!isColorModelSymbol(symbol)) return@analyze null
            val descriptor = ColorRGBaDescriptor.fromCallableName(callableShortName(symbol)) ?: return@analyze null
            val argumentMap = computeValueArguments(functionCall) ?: return@analyze null
            descriptor.colorFromArguments(argumentMap)
        }
    }

    private val COLOR_PROVIDER_PATTERN: PsiElementPattern.Capture<PsiElement> = psiElement(KtTokens.IDENTIFIER)
        // @formatter:off
        // Exclude import statements (which are also dot qualified expressions). The K1 implementation used
        // `.withReference(SyntheticPropertyAccessorReference)` to disambiguate, but that reference type is not
        // produced in K2 mode, so we exclude imports structurally instead.
        .andNot(psiElement().inside(KtImportDirective::class.java))
        .withParent(
            or(
                /** Matches something like **ColorRGBa**.RED */
                psiElement(KtNameReferenceExpression::class.java)
                    .beforeLeaf(psiElement(KtTokens.DOT)
                        .beforeLeaf(psiElement(KtTokens.IDENTIFIER)
                            .beforeLeaf(not(psiElement(KtTokens.LPAR)))))
                    .withParent(KtDotQualifiedExpression::class.java),
                /** Matches something like **ColorRGBa**(...) or ColorRGBa.**fromHex**(...) */
                psiElement(KtNameReferenceExpression::class.java)
                    .beforeLeaf(psiElement(KtTokens.LPAR))
                    .withParent(psiElement(KtCallExpression::class.java))
            )
        )
        // @formatter:on
}
