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
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.openrndr.plugin.intellij.ui.RoundColorIcon
import org.openrndr.plugin.intellij.utils.ColorUtil
import org.openrndr.plugin.intellij.utils.ColorUtil.resolveToColor
import org.openrndr.plugin.intellij.utils.isColorModelType

class ColorRGBaCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? KtFile
        // Names of color-typed properties declared in the file. Used (together with the static color map)
        // to decide which completion items are worth decorating, without resolving every single candidate.
        val colorPropertyNames = file?.let(::collectColorPropertyNames) ?: emptySet()
        result.runRemainingContributors(parameters) { completionResult ->
            val element = completionResult.lookupElement
            // The lookup string is the declaration's simple name for the value completions we care about
            // (e.g. "RED", "myVar0"). This works in both K1 and K2 modes without touching internal lookup
            // object types, and matches how the tests identify these items.
            val name = element.lookupString
            if (file != null && (name in ColorUtil.staticColorMap || name in colorPropertyNames)) {
                result.passResult(completionResult.withLookupElement(element.decorateWithIcon(name, file)))
            } else {
                result.passResult(completionResult)
            }
        }
    }
}

/** Names of all properties in [file] whose type is an openrndr color model. */
private fun collectColorPropertyNames(file: KtFile): Set<String> {
    val properties = PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)
    if (properties.isEmpty()) return emptySet()
    return analyze(file) {
        properties.mapNotNullTo(mutableSetOf()) { property ->
            val name = property.name ?: return@mapNotNullTo null
            if (isColorModelType(property.symbol.returnType)) name else null
        }
    }
}

private fun KtFile.findColorProperty(name: String): KtProperty? =
    PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).firstOrNull { it.name == name }

private fun LookupElement.decorateWithIcon(name: String, file: KtFile) =
    object : LookupElementDecorator<LookupElement>(this) {
        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            val color = ColorUtil.staticColorMap[name] ?: return
            presentation.icon = JBUIScale.scaleIcon(RoundColorIcon(color, 16, 14))
        }

        override fun getExpensiveRenderer() = object : LookupElementRenderer<LookupElement>() {
            override fun renderElement(element: LookupElement?, presentation: LookupElementPresentation) {
                element?.renderElement(presentation)
                // K2 lookup elements carry no PSI, so recover the declaration by name from the file.
                val property = file.findColorProperty(name) ?: return
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
