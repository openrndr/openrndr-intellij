package org.openrndr.plugin.intellij.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.kotlin.idea.core.completion.DeclarationLookupObject
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.Companion.isColorModelPackage
import org.openrndr.plugin.intellij.editor.ColorRGBaColorProvider.Companion.staticColorMap
import org.openrndr.plugin.intellij.ui.RoundColorIcon

class ColorRGBaCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        result.runRemainingContributors(parameters) c@{ completionResult ->
            val lookupElement = completionResult.lookupElement
            val declarationLookupObject = lookupElement.`object` as? DeclarationLookupObject
            val descriptor = declarationLookupObject?.descriptor?.takeIf { it.isColorModelPackage() }
            val color = staticColorMap[descriptor?.name?.identifier] ?: return@c result.passResult(completionResult)
            val decoratedLookupElement = object : LookupElementDecorator<LookupElement>(lookupElement) {
                override fun renderElement(presentation: LookupElementPresentation?) {
                    super.renderElement(presentation)
                    presentation?.icon = JBUIScale.scaleIcon(RoundColorIcon(color, 16, 12))
                }
            }
            result.passResult(completionResult.withLookupElement(decoratedLookupElement))
        }
    }
}
