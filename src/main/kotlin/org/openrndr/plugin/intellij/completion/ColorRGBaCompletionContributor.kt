package org.openrndr.plugin.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.idea.core.completion.DescriptorBasedDeclarationLookupObject
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.openrndr.plugin.intellij.ui.RoundColorIcon
import org.openrndr.plugin.intellij.utils.ColorUtil
import org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor
import org.openrndr.plugin.intellij.utils.DescriptorUtil.isColorModelPackage

class ColorRGBaCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        result.runRemainingContributors(parameters) { completionResult ->
            val element = completionResult.lookupElement
            val lookupObject = element.`object` as? DescriptorBasedDeclarationLookupObject
            val descriptor = (lookupObject?.descriptor as? ValueDescriptor)?.takeIf { it.isColorModelPackage() }
                ?: return@runRemainingContributors result.passResult(completionResult)
            val decoratedLookupElement = element.decorateWithIcon(descriptor)
            result.passResult(completionResult.withLookupElement(decoratedLookupElement))
        }
    }
}

private fun LookupElement.decorateWithIcon(descriptor: ValueDescriptor) =
    object : LookupElementDecorator<LookupElement>(this) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            val color = ColorUtil.staticColorMap[descriptor.name.identifier] ?: return
            presentation.icon = JBUIScale.scaleIcon(RoundColorIcon(color, 16, 14))
        }

        override fun getExpensiveRenderer() = object : LookupElementRenderer<LookupElement>() {
            override fun renderElement(element: LookupElement?, presentation: LookupElementPresentation) {
                element?.renderElement(presentation)
                val property = element?.psiElement as? KtDeclarationWithInitializer ?: return
                val callExpression =
                    PsiTreeUtil.findChildOfType(property.initializer, KtCallExpression::class.java, false)
                        ?: return
                val leaf = PsiTreeUtil.getDeepestFirst(callExpression)
                val color = leaf.resolveToColor() ?: return
                presentation.setTypeText(
                    presentation.typeText, JBUIScale.scaleIcon(RoundColorIcon(color, 16, 14))
                )
            }
        }
    }