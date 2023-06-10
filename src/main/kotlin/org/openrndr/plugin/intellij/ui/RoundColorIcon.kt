package org.openrndr.plugin.intellij.ui

import com.intellij.openapi.ui.GraphicsConfig
import com.intellij.util.ui.JBCachingScalableIcon
import java.awt.*
import kotlin.math.ceil

/**
 * @param color color used for drawing the icon
 * @param size icon size with padding
 * @param colorSize inner size of the icon i.e. the size of the area where the color is drawn within the icon
 */
data class RoundColorIcon(val color: Color, val size: Int, val colorSize: Int) :
    JBCachingScalableIcon<RoundColorIcon>() {
    private val sizeScaled = scaleVal(size.toDouble())
    private val colorSizeScaled = scaleVal(colorSize.toDouble())
    private val arcDiameter = (colorSizeScaled / 2.0).toInt()
    private val offset = ((sizeScaled - colorSizeScaled) / 2.0).toInt()
    private val innerSize = ceil(colorSizeScaled).toInt()

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        with(graphics as Graphics2D) {
            GraphicsConfig(this).setupAAPainting().also {
                color = this@RoundColorIcon.color
                fillRoundRect(x + offset, y + offset, innerSize, innerSize, arcDiameter, arcDiameter)
            }.restore()
        }
    }

    override fun getIconWidth(): Int = ceil(sizeScaled).toInt()
    override fun getIconHeight(): Int = ceil(sizeScaled).toInt()
    override fun copy(): RoundColorIcon = this.copy()
}
