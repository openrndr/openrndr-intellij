package org.openrndr.plugin.intellij.ui

import com.intellij.util.ui.JBCachingScalableIcon
import java.awt.*
import kotlin.math.ceil
import kotlin.math.floor

data class RoundColorIcon(val size: Int, val colorSize: Int, val color: Color) :
    JBCachingScalableIcon<RoundColorIcon>() {
    private val sizeScaled = scaleVal(size.toDouble())
    private val colorSizeScaled = scaleVal(colorSize.toDouble())

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val offset = floor((sizeScaled - colorSizeScaled) / 2.0).toInt()
        val adjustedColorSize = ceil(colorSizeScaled).toInt()
        with(graphics as Graphics2D) {
            color = this@RoundColorIcon.color
            val hint = getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fillOval(x + offset, y + offset, adjustedColorSize, adjustedColorSize)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, hint)
        }
    }

    override fun getIconWidth(): Int = ceil(sizeScaled).toInt()
    override fun getIconHeight(): Int = ceil(sizeScaled).toInt()
    override fun copy(): RoundColorIcon = this.copy()
}
