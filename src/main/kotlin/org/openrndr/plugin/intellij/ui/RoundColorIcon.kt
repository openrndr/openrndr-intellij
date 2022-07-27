package org.openrndr.plugin.intellij.ui

import com.intellij.util.ui.JBScalableIcon
import java.awt.*
import kotlin.math.ceil
import kotlin.math.floor

data class RoundColorIcon(val size: Int, val colorSize: Int, val color: Color) : JBScalableIcon() {
    override fun paintIcon(component: Component, graphics: Graphics, left: Int, top: Int) {
        val scaledColorSize = scaleVal(colorSize.toDouble())
        val x = left + floor((iconWidth - scaledColorSize) / 2.0).toInt()
        val y = top + floor((iconHeight - scaledColorSize) / 2.0).toInt()
        val adjustedColorSize = ceil(scaledColorSize).toInt()
        with(graphics as Graphics2D) {
            color = this@RoundColorIcon.color
            val hint = getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fillOval(x, y, adjustedColorSize, adjustedColorSize)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, hint)
        }
    }

    override fun getIconWidth(): Int = ceil(scaleVal(size.toDouble())).toInt()

    override fun getIconHeight(): Int = iconWidth
}
