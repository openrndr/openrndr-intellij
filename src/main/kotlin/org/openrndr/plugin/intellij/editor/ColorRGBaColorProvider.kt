package org.openrndr.plugin.intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import com.intellij.openapi.editor.ElementColorProvider
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes2
import org.openrndr.plugin.intellij.OpenrndrBundle
import org.openrndr.plugin.intellij.utils.ResolvedArgInfo
import org.openrndr.plugin.intellij.utils.callableShortName
import org.openrndr.plugin.intellij.utils.computeValueArguments
import org.openrndr.plugin.intellij.utils.isColorRGBaStatic
import org.openrndr.plugin.intellij.utils.resolvedColorArguments
import org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor
import java.awt.Color

class ColorRGBaColorProvider : ElementColorProvider {

    override fun getColorFrom(element: PsiElement): Color? = element.resolveToColor()

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val project = element.project
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        val outerExpression =
            element.getParentOfTypes2<KtCallExpression, KtDotQualifiedExpression>() as? KtExpression ?: return

        // Resolution must happen outside the write command: the Analysis API forbids `analyze {}` from a
        // write action. We extract everything we need as plain data / PSI here, then mutate the PSI below.
        val replacement = analyze(outerExpression) { computeReplacement(outerExpression, color) } ?: return

        val command = Runnable { applyReplacement(outerExpression, replacement, project) }
        CommandProcessor.getInstance()
            .executeCommand(project, command, OpenrndrBundle.message("change.color.command.text"), null, document)
    }

    /**
     * Resolves [outerExpression] and produces a descriptor-free [ColorReplacement] describing how to edit
     * the source to match [color], or `null` if the expression is not an editable color expression.
     */
    private fun KaSession.computeReplacement(outerExpression: KtExpression, color: Color): ColorReplacement? {
        val callInfo = outerExpression.resolveToCall() ?: return null

        callInfo.successfulFunctionCallOrNull()?.let { functionCall ->
            val descriptor = ColorRGBaDescriptor.fromCallableName(callableShortName(functionCall.symbol)) ?: return null
            val argumentMap = computeValueArguments(functionCall)
            val ref = argumentMap?.values
                ?.firstNotNullOfOrNull { it as? ConstantValueContainer.WhitePoint }?.value
            val colorArguments = descriptor.argumentsFromColor(color, ref)
            return ColorReplacement.Arguments(resolvedColorArguments(functionCall), colorArguments)
        }

        callInfo.successfulVariableAccessCall()?.let { variableAccess ->
            /**
             * This part handles the scenario where the user had a [org.openrndr.color.ColorRGBa.RED] but
             * still wants to use the color picker to choose a new color. It's a miracle it works in the
             * first place but the gist of it is that you can't replace the element that was provided through
             * setColorTo, because once you replace it but continue dragging through the color picker, it
             * will call setColorTo again with the same element that you just replaced. In which case it all
             * comes tumbling down because IntelliJ doesn't like dealing with non-existent elements.
             * We can alleviate this in [resolveToColor] by only returning a [Color] for the **ColorRGBa**.RED
             * part, not for ColorRGBa.**RED**. This way we can replace `RED` without touching our actual
             * element, `ColorRGBa`. So far so good. But if we want to replace it with a `ColorRGBa(...)` we
             * need to replace `RED` with `(...)` but there's also the "dot" between them. Since ColorRGBa as
             * a whole is a [KtDotQualifiedExpression], removing the dot would leave us with an invalid PSI
             * structure and IntelliJ *really* doesn't like that.
             * Now we arrive at our approach. We can replace `RED` with `fromHex(...)`, preserving the dot,
             * preserving our element and therefore avoid breaking the PSI structure. It looks contrived but
             * after numerous approaches, this actually started to seem like the only one viable.
             */
            if (!isColorRGBaStatic(variableAccess.symbol)) return null
            val hexArgument = ColorRGBaDescriptor.FromHex.argumentsFromColor(color, null).firstOrNull() ?: return null
            return ColorReplacement.StaticColorRGBa(hexArgument)
        }

        return null
    }

    private fun applyReplacement(outerExpression: KtExpression, replacement: ColorReplacement, project: Project) {
        val psiFactory = KtPsiFactory(project)
        when (replacement) {
            is ColorReplacement.StaticColorRGBa ->
                (outerExpression as? KtDotQualifiedExpression)?.selectorExpression?.replace(
                    psiFactory.createExpression("fromHex(${replacement.hexArgument})")
                )

            is ColorReplacement.Arguments -> {
                outerExpression.getChildOfType<KtValueArgumentList>()?.let {
                    it.replace(it.constructReplacement(replacement.resolvedArgs, replacement.colorArguments))
                } ?: outerExpression.getChildOfType<KtCallExpression>()?.let {
                    // This handles the scenario after ColorRGBa.RED has been replaced by ColorRGBa.fromHex(...)
                    // without closing the color picker and picking a new color. I think IntelliJ has not yet
                    // recognized the PSI structure changes and is unaware we now have a KtValueArgumentList
                    // following the KtCallExpression.
                    it.replace(
                        psiFactory.createExpression("fromHex(${replacement.colorArguments.firstOrNull() ?: return})")
                    )
                }
            }
        }
    }

    /** A descriptor-free, `analyze`-block-free description of how to rewrite a color expression. */
    private sealed interface ColorReplacement {
        /** Replace the `RED` selector of `ColorRGBa.RED` with `fromHex(...)`. */
        class StaticColorRGBa(val hexArgument: String) : ColorReplacement

        /** Rewrite the value-argument list of a color constructor / function call. */
        class Arguments(val resolvedArgs: List<ResolvedArgInfo>, val colorArguments: Array<String>) : ColorReplacement
    }

    /**
     * Non-destructively builds and returns a new [KtValueArgumentList].
     *
     * @param resolvedArgs the resolved arguments (in parameter order) of the original call
     * @param replacementArguments replacement arguments which are retrieved by parameter index and
     * converted into [KtExpression]s
     */
    private fun KtValueArgumentList.constructReplacement(
        resolvedArgs: List<ResolvedArgInfo>, replacementArguments: Array<String>
    ): KtValueArgumentList {
        val psiFactory = KtPsiFactory.contextual(this, true)

        // It handles overloads where the resolved function call is not the one we want anymore
        // because it is incapable of expressing the desired color accurately, such as `rgb` with 2 arguments.
        // Potentially incorrect because we're making an assumption the caller is passing correct arguments.
        if (resolvedArgs.size < replacementArguments.size - 1) {
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
            for (arg in resolvedArgs) {
                val newArgument = replacementArguments.getOrNull(arg.index)
                val valueArgument = arg.valueArgument
                if (valueArgument != null) {
                    if (valueArgument != firstValueArgument) appendFixedText(", ")
                    valueArgument.getArgumentName()?.asName?.let {
                        appendName(it)
                        appendFixedText(" = ")
                    }
                    appendExpression(
                        newArgument?.let(psiFactory::createExpressionIfPossible)
                            ?: valueArgument.getArgumentExpression()
                    )
                } else {
                    // Defaulted parameter: only emit it if we have a replacement value for its index.
                    newArgument?.let {
                        if (arg.index > 0) appendFixedText(", ")
                        appendName(Name.identifier(arg.name))
                        appendFixedText(" = ")
                        appendExpression(psiFactory.createExpression(it))
                    }
                }
            }
            appendFixedText(")")
        }
    }
}
