package org.openrndr.plugin.intellij.utils

import com.intellij.patterns.PlatformPatterns.*
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.references.SyntheticPropertyAccessorReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.jetbrains.kotlin.resolve.calls.model.KotlinCallKind
import org.jetbrains.kotlin.resolve.calls.tower.NewAbstractResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.ColorModel
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorXYZa
import org.openrndr.color.Linearity
import org.openrndr.plugin.intellij.editor.ColorRGBaDescriptor
import org.openrndr.plugin.intellij.utils.DescriptorUtil.computeValueArguments
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isColorModelPackage
import java.awt.Color
import kotlin.reflect.full.memberProperties


@Suppress("UseJBColor")
internal object ColorUtil {
    val colorRGBaFieldNames = arrayOf("r", "g", "b", "alpha")
    val defaultColorRGBa = ColorRGBa(1.0, 1.0, 1.0, 1.0, Linearity.UNKNOWN)

    fun ColorModel<*>.toAWTColor(): Color = toRGBa().run {
        Color(
            r.toFloat().coerceIn(0f, 1f),
            g.toFloat().coerceIn(0f, 1f),
            b.toFloat().coerceIn(0f, 1f),
            alpha.toFloat().coerceIn(0f, 1f)
        )
    }

    fun Color.toColorRGBa(linearity: Linearity = Linearity.UNKNOWN) = getComponents(null).let { (r, g, b, a) ->
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
        val outerExpressionContext = outerExpression?.analyze() ?: return null
        val resolvedCall = outerExpression.getResolvedCall(outerExpressionContext) as? NewAbstractResolvedCall
        val descriptor = resolvedCall?.resultingDescriptor

        if (descriptor?.isColorModelPackage() != true) return null
        if (resolvedCall.kotlinCall?.callKind == KotlinCallKind.VARIABLE) {
            return staticColorMap[descriptor.getImportableDescriptor().name.identifier]
        }

        val argumentMap = resolvedCall.computeValueArguments(outerExpressionContext) ?: return null
        val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor)
        return colorRGBaDescriptor?.colorFromArguments(argumentMap)
    }

    private val COLOR_PROVIDER_PATTERN: PsiElementPattern.Capture<PsiElement> = psiElement(KtTokens.IDENTIFIER)
        // @formatter:off
        .withParent(
            or(
                /** Matches something like **ColorRGBa**.RED */
                psiElement(KtNameReferenceExpression::class.java)
                    // This disambiguates from import statements which are also dot qualified expressions
                    .withReference(SyntheticPropertyAccessorReference::class.java)
                    .beforeLeaf(psiElement(KtTokens.DOT)
                        .beforeLeaf(psiElement(KtTokens.IDENTIFIER)
                            .beforeLeaf(not(psiElement(KtTokens.LPAR)))))
                    .withParent(KtDotQualifiedExpression::class.java),
                /** Matches something like **ColorRGBa**(...) or ColorRGBa.**fromHex**(...) */
                psiElement(KtNameReferenceExpression::class.java)
                    .withReference(SyntheticPropertyAccessorReference::class.java)
                    .beforeLeaf(psiElement(KtTokens.LPAR))
                    .withParent(psiElement(KtCallExpression::class.java))
            )
        )
        // @formatter:on
}