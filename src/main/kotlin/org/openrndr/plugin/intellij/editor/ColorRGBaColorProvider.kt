package org.openrndr.plugin.intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.getTopLevelContainingClassifier
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument
import org.jetbrains.kotlin.resolve.calls.tower.NewAbstractResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ReferenceWhitePoint
import org.openrndr.plugin.intellij.OpenrndrBundle
import org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor
import org.openrndr.plugin.intellij.utils.DescriptorUtil.computeValueArguments
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isRef
import java.awt.Color

class ColorRGBaColorProvider : ElementColorProvider {
    companion object {
        private val LOG = logger<ColorRGBaColorProvider>()
    }

    override fun getColorFrom(element: PsiElement): Color? = element.resolveToColor()

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
                 * We can alleviate this in [resolveToColor] by only returning a [Color] for the **ColorRGBa**.RED part,
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
                @Suppress("SimpleRedundantLet")
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
     * Non-destructively builds and returns a new [KtValueArgumentList].
     *
     * @param replacementArguments replacement arguments which are
     * retrieved by parameter index and converted into [KtExpression]s
     */
    private fun KtValueArgumentList.constructReplacement(
        resolvedArgumentMap: Map<ValueParameterDescriptor, ResolvedValueArgument>, replacementArguments: Array<String>
    ): KtValueArgumentList {
        val psiFactory = KtPsiFactory.contextual(this, true)

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
}