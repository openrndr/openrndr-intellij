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
import java.lang.reflect.Modifier
import kotlin.reflect.full.memberProperties


/**
 * Central helpers for converting between openrndr colors and AWT [Color]s, and for deciding whether a piece
 * of Kotlin code represents an openrndr color.
 *
 * The marquee function is [resolveToColor], which the gutter color provider, the completion contributor, and
 * the debugger renderer all rely on to answer "is this a color, and which one?" The reflection-built
 * [staticColorMap] / [staticWhitePointMap] let us recognise named constants like `ColorRGBa.RED` without
 * hardcoding them.
 */
@Suppress("UseJBColor")
internal object ColorUtil {
    /** The component field names of `ColorRGBa`, in source order; used to read color fields over JDI in the debugger. */
    val colorRGBaFieldNames = arrayOf("r", "g", "b", "alpha")

    /** A reference opaque white used as the "neutral" starting color when computing default linearities. */
    val defaultColorRGBa = ColorRGBa(1.0, 1.0, 1.0, 1.0, Linearity.LINEAR)

    /**
     * Converts any openrndr color model to the AWT [Color] that should be shown on screen, clamping each
     * component into `[0, 1]`.
     *
     * A screen displays sRGB-encoded values, so we must gamma-encode before reading components: many models'
     * [ColorModel.toRGBa] returns a **linear**-light [ColorRGBa] (e.g. the `ColorRGBa(...)` constructor, whose
     * `linearity` defaults to [Linearity.LINEAR], and the LAB/XYZ/LCH/LUV/OKLab/Yxy families), and showing those
     * linear components raw would render the swatch too dark. [ColorRGBa.toSRGB] is a no-op for colors that are
     * already sRGB (HSL/HSV/`rgb()`/`fromHex`/the static constants), so this is correct for every model.
     */
    fun ColorModel<*>.toAWTColor(): Color = toRGBa().toSRGB().run {
        Color(
            r.toFloat().coerceIn(0f, 1f),
            g.toFloat().coerceIn(0f, 1f),
            b.toFloat().coerceIn(0f, 1f),
            alpha.toFloat().coerceIn(0f, 1f)
        )
    }

    /**
     * Converts an AWT [Color] (always sRGB) into a [ColorRGBa] of the given [linearity], **converting** the
     * components into that linearity rather than merely relabelling them. This is the inverse of [toAWTColor]:
     * `someColor.toColorRGBa(l).toAWTColor() == someColor` for any [l], which is what makes the color picker
     * round-trip — a color written into a `Linearity.LINEAR` expression is stored as its linear-light values.
     */
    fun Color.toColorRGBa(linearity: Linearity = Linearity.SRGB) = getComponents(null).let { (r, g, b, a) ->
        ColorRGBa(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble(), Linearity.SRGB).toLinearity(linearity)
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
        // There's no easy way to get the ColorRGBa extension properties in orx, we have to use Java reflection.
        // Each preset is a lazy-delegated extension property, so orx compiles it into BOTH a public getter
        // `getNAME(ColorRGBa.Companion): ColorRGBa` AND a `private static` lazy-initializer lambda
        // (`NAME_delegate$lambda$N()`). We must invoke only the public getters: invoking a private lambda throws
        // IllegalAccessException (and it takes no args anyway). Older orx emitted those lambdas as separate
        // synthetic classes, so the unfiltered loop happened to work before the dependency bump.
        val companionClass = ColorRGBa.Companion::class.java
        val extensionColorsJavaClass = Class.forName("org.openrndr.extra.color.presets.ColorsKt")
        for (method in extensionColorsJavaClass.declaredMethods) {
            if (!Modifier.isPublic(method.modifiers)) continue
            if (method.returnType != ColorRGBa::class.java) continue
            if (method.parameterCount != 1 || method.parameterTypes[0] != companionClass) continue
            this[method.name.removePrefix("get")] =
                (method.invoke(null, ColorRGBa.Companion) as ColorRGBa).toAWTColor()
        }
    }

    /**
     * Name-to-white-point mapping of all static [ColorXYZa] constants (e.g. `SO10_D65`). Used to resolve the
     * `ref` argument of reference-white-point color models when it is written as a named constant.
     */
    val staticWhitePointMap: Map<String, ColorXYZa> = buildMap {
        // ColorXYZa static white points
        for (property in ColorXYZa.Companion::class.memberProperties) {
            this[property.name] = property.getter.call(ColorXYZa.Companion) as ColorXYZa
        }
    }

    /**
     * The core "what color, if any, is this element?" entry point shared across the plugin.
     *
     * Returns `null` unless [this] is the identifier leaf of a color expression we recognise (gated cheaply by
     * [COLOR_PROVIDER_PATTERN] before any expensive resolution). It then resolves the surrounding call inside an
     * `analyze {}` block and produces a [Color] for either a static color constant (`ColorRGBa.RED`) or a
     * constructor / factory call (`ColorRGBa(...)`, `rgb(...)`, `ColorRGBa.fromHex(...)`, every orx color model).
     */
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

    /**
     * A cheap, purely structural pre-filter run on every leaf before [resolveToColor] resolves anything.
     * It matches the identifier of either a `Foo.BAR` static access or a `Foo(...)` / `Foo.bar(...)` call,
     * while excluding import statements. Keeping resolution off the vast majority of leaves is what keeps the
     * gutter color provider fast enough to run on every element in the file.
     */
    private val COLOR_PROVIDER_PATTERN: PsiElementPattern.Capture<PsiElement> = psiElement(KtTokens.IDENTIFIER)
        // @formatter:off
        // Exclude import statements (which are also dot-qualified expressions). The K1 implementation used
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
