package org.openrndr.plugin.intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.patterns.PlatformPatterns.*
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.idea.references.SyntheticPropertyAccessorReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.parentOrNull
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tower.NewAbstractResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import org.openrndr.plugin.intellij.OpenrndrBundle
import org.openrndr.plugin.intellij.editor.ColorRGBaDescriptor.Companion.colorComponents
import org.openrndr.plugin.intellij.editor.ConstantValueContainer.Companion.getDefaultValueIfKnown
import org.openrndr.plugin.intellij.editor.ConstantValueContainer.Companion.isRef
import java.awt.Color
import kotlin.reflect.full.memberProperties

object ColorRGBaColorProvider : ElementColorProvider {
    private val LOG = logger<ColorRGBaColorProvider>()

    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        if (!COLOR_PROVIDER_PATTERN.accepts(element)) return null
        val outerExpression = element.getParentOfTypes2<KtCallExpression, KtDotQualifiedExpression>() as? KtExpression
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

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val project = element.project
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        val command = Runnable r@{
            val outerExpression =
                element.getParentOfTypes2<KtCallExpression, KtDotQualifiedExpression>() as? KtExpression
            val outerExpressionContext = outerExpression?.analyze() ?: return@r
            val call = outerExpression.getCall(outerExpressionContext)
            val resolvedCall = call?.getResolvedCall(outerExpressionContext) as? NewAbstractResolvedCall ?: return@r

            val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(resolvedCall.resultingDescriptor)
            val psiFactory = KtPsiFactory(project)
            if (colorRGBaDescriptor == null) {
                val classDescriptor =
                    (resolvedCall.resultingDescriptor as? DeclarationDescriptor)?.getTopLevelContainingClassifier() as? ClassDescriptor
                if (classDescriptor?.name?.identifier != "ColorRGBa") return@r
                /**
                 * This part handles the scenario where the user had a [ColorRGBa.RED] but still wants to use the
                 * color picker to choose a new color. It's a miracle it works in the first place but the gist of it
                 * is that you can't replace the element that was provided through setColorTo, because once you replace
                 * it but continue dragging through the color picker, it will call setColorTo again with the same
                 * element that you just replaced. In which case it all comes tumbling down because IntelliJ doesn't
                 * like dealing with non-existent elements.
                 * We can alleviate this in [getColorFrom] by only returning a [Color] for the **ColorRGBa**.RED part,
                 * not for ColorRGBa.**RED**. This way we can replace `RED` without touching our actual element,
                 * `ColorRGBa`. So far so good. But if we want to replace it with a `ColorRGBa(...)` we need to replace
                 * `RED` with `(...)` but there's also the "dot" between them. Since ColorRGBa as a whole is a
                 * [KtDotQualifiedExpression], removing the dot would leave us with an invalid PSI structure and
                 * IntelliJ *really* doesn't like that.
                 * Now we arrive at our approach. We can replace `RED` with `fromHex(...)`, preserving the dot,
                 * preserving our element and therefore avoid breaking the PSI structure. It looks contrived but
                 * after numerous approaches, this actually started to seem like the only one viable.
                 * I think the proper way to do this to extend our own codeInsight.lineMarkerProvider giving us full
                 * control over the color picker behavior.
                 */
                val hexArgument = ColorRGBaDescriptor.FromHex.argumentsFromColor(color, null).firstOrNull() ?: return@r
                (outerExpression as? KtDotQualifiedExpression)?.selectorExpression?.replace(
                    psiFactory.createExpression("fromHex($hexArgument)")
                )
            } else {
                /**
                 * This will always be null for color models which don't implement [ReferenceWhitePoint]
                 * and always non-null for color models that do.
                 */
                val argumentMap = resolvedCall.computeValueArguments(outerExpressionContext)
                val ref =
                    argumentMap?.firstNotNullOfOrNull { it.takeIf { (p, _) -> p.isRef() }?.value } as? ConstantValueContainer.WhitePoint
                val colorArguments = colorRGBaDescriptor.argumentsFromColor(color, ref?.value)
                outerExpression.getChildOfType<KtValueArgumentList>()?.let {
                    it.replace(it.constructReplacement(resolvedCall.valueArguments, colorArguments))
                } ?: outerExpression.getChildOfType<KtCallExpression>()?.let {
                    // This handles the scenario after ColorRGBa.RED has been replaced by ColorRGBa.fromHex(...) without
                    // closing the color picker and picking a new color. I think IntelliJ has not yet recognized the PSI
                    // structure changes and is unaware we now have a KtValueArgumentList following the KtCallExpression.
                    it.replace(psiFactory.createExpression("fromHex(${colorArguments.firstOrNull() ?: return@r})"))
                }
            }
        }
        CommandProcessor.getInstance()
            .executeCommand(project, command, OpenrndrBundle.message("change.color.command.text"), null, document)
    }

    /**
     * Computes argument constants if it can, returns null otherwise.
     *
     * @return Either a mapping of all parameters to argument constants or null if any of the argument
     * constants cannot be computed.
     */
    private fun ResolvedCall<out CallableDescriptor>.computeValueArguments(bindingContext: BindingContext): ArgumentMap? =
        buildMap {
            for ((parameter, argument) in valueArguments) {
                val expression = (argument as? ExpressionValueArgument)?.valueArgument?.getArgumentExpression()
                this[parameter] = if (expression == null) {
                    parameter.getDefaultValueIfKnown() ?: return null
                } else if (parameter.isRef()) {
                    val refContext = expression.analyze()
                    val refResolvedCall = expression.getResolvedCall(refContext) ?: return null
                    val importableDescriptor = refResolvedCall.resultingDescriptor.getImportableDescriptor()
                    val refColor = staticWhitePointMap[importableDescriptor.name.identifier]
                        ?: refResolvedCall.computeValueArguments(refContext)?.computeWhitePoint() ?: return null
                    ConstantValueContainer.WhitePoint(refColor)
                } else {
                    val constant = ConstantExpressionEvaluator.getConstant(expression, bindingContext)
                        ?.toConstantValue(parameter.type) ?: return null
                    ConstantValueContainer.Constant(constant)
                }
            }
        }

    private fun ArgumentMap.computeWhitePoint(): ColorXYZa? = colorComponents.let {
        when (it.size) {
            3 -> ColorXYZa(it[0], it[1], it[2], 1.0)
            4 -> ColorXYZa(it[0], it[1], it[2], it[3])
            else -> null
        }
    }

    /**
     * Non-destructively builds and returns a new [KtValueArgumentList].
     *
     * @param replacementArguments replacement arguments which are
     * retrieved by parameter index and converted into [KtExpression]s
     */
    private fun KtValueArgumentList.constructReplacement(
        resolvedArgumentMap: Map<ValueParameterDescriptor, ResolvedValueArgument>, replacementArguments: Array<String>
    ): KtValueArgumentList {
        val psiFactory = KtPsiFactory(this)

        // It handles overloads where the resolved function call is not the one we want anymore
        // because it is incapable of expressing the desired color accurately, such as `rgb` with 2 arguments.
        // Potentially incorrect because we're making an assumption the caller is passing correct arguments.
        if (resolvedArgumentMap.size < replacementArguments.size - 1) {
            return psiFactory.buildValueArgumentList {
                appendFixedText("(")
                repeat(replacementArguments.size) {
                    if (it != 0) appendFixedText(", ")
                    appendFixedText(replacementArguments[it])
                }
                appendFixedText(")")
            }
        }

        val firstValueArgument = arguments.firstOrNull()
        return psiFactory.buildValueArgumentList {
            appendFixedText("(")
            for ((parameter, argument) in resolvedArgumentMap) {
                val newArgument = replacementArguments.getOrNull(parameter.index)
                when (argument) {
                    is ExpressionValueArgument -> {
                        val valueArgument = argument.valueArgument!!
                        if (valueArgument != firstValueArgument) appendFixedText(", ")
                        valueArgument.getArgumentName()?.asName?.let {
                            appendName(it)
                            appendFixedText(" = ")
                        }
                        appendExpression(
                            newArgument?.let(psiFactory::createExpressionIfPossible)
                                ?: valueArgument.getArgumentExpression()
                        )
                    }
                    is DefaultValueArgument -> {
                        newArgument?.let {
                            if (parameter.index > 0) appendFixedText(", ")
                            appendName(parameter.name)
                            appendFixedText(" = ")
                            appendExpression(psiFactory.createExpression(it))
                        }
                    }
                    else -> LOG.error("Parameter $parameter has unhandled argument $argument")
                }
            }
            appendFixedText(")")
        }
    }

    private fun isColorModelPackage(s: String?): Boolean =
        s == "org.openrndr.color" || s == "org.openrndr.extra.color.presets" || s == "org.openrndr.extra.color.spaces"

    internal fun DeclarationDescriptor.isColorModelPackage(): Boolean =
        containingPackage()?.asString().let(::isColorModelPackage)

    internal fun ValueDescriptor.isColorModelPackage(): Boolean =
        type.fqName?.parentOrNull()?.asString().let(::isColorModelPackage)

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
    internal val staticColorMap: Map<String, Color> = buildMap {
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

    private val staticWhitePointMap: Map<String, ColorXYZa> = buildMap {
        // ColorXYZa static white points
        for (property in ColorXYZa.Companion::class.memberProperties) {
            this[property.name] = property.getter.call(ColorXYZa.Companion) as ColorXYZa
        }
    }

    // @formatter:off
    private val COLOR_PROVIDER_PATTERN: PsiElementPattern.Capture<PsiElement> = psiElement(KtTokens.IDENTIFIER)
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