package org.openrndr.plugin.intellij.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
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
import org.openrndr.plugin.intellij.utils.hasIntComponents
import org.openrndr.plugin.intellij.utils.linearity
import org.openrndr.plugin.intellij.utils.isColorRGBaStatic
import org.openrndr.plugin.intellij.utils.resolvedColorArguments
import org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor
import java.awt.Color

/**
 * # Gutter color previews and color picker
 *
 * Drives the color gutter icon and color picker for openrndr color expressions in Kotlin code.
 *
 * IntelliJ calls [ElementColorProvider] for every leaf PSI element to ask two questions:
 *  - [getColorFrom]: "does this element represent a color, and if so which one?" A non-null answer makes
 *    IntelliJ render a clickable color swatch in the editor gutter.
 *  - [setColorTo]: "the user picked a new color in the swatch's color picker — rewrite the source to match."
 *
 * The "what color is this expression" logic lives in [org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor];
 * this class focuses on the harder direction — turning a chosen [Color] back into edited source code.
 */
class ColorRGBaColorProvider : ElementColorProvider {

    /** Returns the color a gutter swatch should show for [element], or `null` if it is not a color expression. */
    override fun getColorFrom(element: PsiElement): Color? = element.resolveToColor()

    /**
     * Rewrites the color expression containing [element] so it evaluates to [color], invoked when the user
     * picks a new color in the gutter swatch's color picker.
     *
     * Symbol resolution (the `analyze {}` block) and PSI mutation cannot happen together, so this is split
     * in two: [computeReplacement] resolves the call into plain data while reading, then [applyReplacement]
     * mutates the PSI inside a write command (so the edit is undoable as a single step).
     */
    @OptIn(KaAllowAnalysisOnEdt::class)
    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val project = element.project
        val outerExpression =
            element.getParentOfTypes2<KtCallExpression, KtDotQualifiedExpression>() as? KtExpression ?: return

        // Resolution must happen outside the write command: the Analysis API forbids `analyze {}` from a
        // write action. We extract everything we need as plain data / PSI here, then mutate the PSI below.
        // (it is a compile error, not just a runtime one, because it can freeze the IDE), so we cannot resolve
        // here. Instead we defer with invokeLater: by the time the runnable executes the platform's write action
        // has finished, so resolution runs on the EDT but outside any write lock (allowed via the opt-in), and we
        // then perform the PSI edit in our own write command so it stays a single undoable step.
        ApplicationManager.getApplication().invokeLater {
            if (!outerExpression.isValid) return@invokeLater
            val replacement = allowAnalysisOnEdt {
                analyze(outerExpression) { computeReplacement(outerExpression, color) }
            } ?: return@invokeLater
            WriteCommandAction.runWriteCommandAction(
                project,
                OpenrndrBundle.message("change.color.command.text"),
                null,
                { applyReplacement(outerExpression, replacement, project) },
                element.containingFile
            )
        }
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
            val linearity = argumentMap?.linearity ?: ConstantValueContainer.DEFAULT_LINEARITY
            // The Int `rgb(red, green, blue, alpha)` overload (0-255 sRGB) keeps its integer form instead of
            // writing doubles, which would silently switch the call to the linear double overload on openrndr
            // 0.5.0. It rebuilds positionally so a fully-opaque alpha can fall back to its 255 default even when
            // the original call passed alpha explicitly.
            val isIntRgb = descriptor == ColorRGBaDescriptor.RGB && argumentMap?.hasIntComponents == true
            val colorArguments =
                if (isIntRgb) intRgbArguments(color) else descriptor.argumentsFromColor(color, ref, linearity)
            return ColorReplacement.Arguments(
                resolvedColorArguments(functionCall), colorArguments, positional = isIntRgb
            )
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
            // A static `ColorRGBa.RED` is rewritten as `fromHex(...)`, which ignores linearity (it emits a hex
            // string), so the value passed here is irrelevant.
            val hexArgument = ColorRGBaDescriptor.FromHex
                .argumentsFromColor(color, null, ConstantValueContainer.DEFAULT_LINEARITY).firstOrNull() ?: return null
            return ColorReplacement.StaticColorRGBa(hexArgument)
        }

        return null
    }

    /**
     * Mutates the PSI of [outerExpression] to apply [replacement]. Must be called from within a write command
     * (see [setColorTo]); it performs no resolution, only the structural edits decided by [computeReplacement].
     */
    private fun applyReplacement(outerExpression: KtExpression, replacement: ColorReplacement, project: Project) {
        val psiFactory = KtPsiFactory(project)
        when (replacement) {
            is ColorReplacement.StaticColorRGBa ->
                (outerExpression as? KtDotQualifiedExpression)?.selectorExpression?.replace(
                    psiFactory.createExpression("fromHex(${replacement.hexArgument})")
                )

            is ColorReplacement.Arguments -> {
                outerExpression.getChildOfType<KtValueArgumentList>()?.let {
                    it.replace(
                        it.constructReplacement(
                            replacement.resolvedArgs, replacement.colorArguments, replacement.positional
                        )
                    )
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

        /**
         * Rewrite the value-argument list of a color constructor / function call. When [positional], the list
         * is rebuilt purely from [colorArguments] (no per-argument preservation), which lets a dropped trailing
         * argument actually disappear — e.g. an opaque alpha falling back to its default in `rgb(Int, …)`.
         */
        class Arguments(
            val resolvedArgs: List<ResolvedArgInfo>,
            val colorArguments: Array<String>,
            val positional: Boolean = false,
        ) : ColorReplacement
    }

    /**
     * Non-destructively builds and returns a new [KtValueArgumentList].
     *
     * @param resolvedArgs the resolved arguments (in parameter order) of the original call
     * @param replacementArguments replacement arguments which are retrieved by parameter index and
     * converted into [KtExpression]s
     * @param positional rebuild the list purely from [replacementArguments], dropping the original arguments
     * entirely (used to let a trailing default-valued argument disappear)
     */
    private fun KtValueArgumentList.constructReplacement(
        resolvedArgs: List<ResolvedArgInfo>, replacementArguments: Array<String>, positional: Boolean = false
    ): KtValueArgumentList {
        val psiFactory = KtPsiFactory.contextual(this, true)

        // Rebuild a fresh positional list when asked, or when the resolved overload can't express the color
        // accurately (e.g. `rgb` with 2 arguments). Potentially incorrect because we're making an assumption
        // the caller is passing correct arguments.
        if (positional || resolvedArgs.size < replacementArguments.size - 1) {
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

/**
 * The 0-255 integer components written back into an Int `rgb(red, green, blue, alpha = 255)` call for [color].
 * A fully-opaque alpha (255) is omitted so the call falls back to the default rather than spelling it out.
 */
internal fun intRgbArguments(color: Color): Array<String> =
    if (color.alpha == 255) arrayOf("${color.red}", "${color.green}", "${color.blue}")
    else arrayOf("${color.red}", "${color.green}", "${color.blue}", "${color.alpha}")
