package org.openrndr.plugin.intellij

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.codeInsight.daemon.LineMarkerSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.ui.ColorLineMarkerProvider
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ColorIcon
import java.awt.Color

/**
 * Gutter swatches for colors the picker cannot write back (`ColorLABa(…)`, `ColorOKLABa(…)`, …). The platform's
 * swatch for an [ElementColorProvider] color always opens the picker, so these are line markers of their own that
 * look the same and follow the platform's "Color preview" gutter setting.
 */
class OpenrndrReadOnlyColorMarkers : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // Colors are computed with analysis, so only in the slow pass, like the platform's swatches
    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (!LineMarkerSettings.getSettings().isEnabled(ColorLineMarkerProvider.INSTANCE)) return
        val provider = ElementColorProvider.EP_NAME.findExtension(OpenrndrColorProvider::class.java) ?: return
        for (element in elements) {
            val color = provider.readOnlyColorOf(element) ?: continue
            result += Swatch(element, color)
        }
    }

    internal class Swatch(element: PsiElement, color: Color) : LineMarkerInfo<PsiElement>(
        element, element.textRange, JBUIScale.scaleIcon(ColorIcon(12, color)),
        null, null, GutterIconRenderer.Alignment.LEFT, { "Color" },
    ) {
        /**
         * The gutter takes a click on an icon with a click action and runs it; without one the click falls through
         * to the gutter itself and toggles a breakpoint. So the click is taken and nothing happens. The hand cursor
         * only depends on [isNavigateAction], which stays false.
         */
        override fun createGutterRenderer(): GutterIconRenderer = object : LineMarkerGutterIconRenderer<PsiElement>(this) {
            override fun getClickAction(): AnAction = IGNORE_CLICK
            override fun isNavigateAction(): Boolean = false
        }
    }

    private companion object {
        val IGNORE_CLICK: AnAction = object : AnAction(), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {}
        }
    }
}
