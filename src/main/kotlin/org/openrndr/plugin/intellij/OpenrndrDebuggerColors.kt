package org.openrndr.plugin.intellij

import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.ValueIconRenderer
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ColorIcon
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Type
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import javax.swing.Icon
import kotlin.math.pow

/**
 * A color swatch next to OPENRNDR color values in the debugger (Variables, Watches, inline values), matching
 * the gutter swatches: what is shown is the on-screen (sRGB) color.
 *
 * - `ColorRGBa` is read straight from its fields, taking its `linearity` into account, without running code
 *   in the program being debugged.
 * - Every other color model (`ColorHSVa`, `ColorLABa`, orx's `ColorOKLABa`, …) is converted by the program
 *   itself with `toRGBa()` first.
 *
 * Ordered first: the debugger uses the first renderer that applies, and Kotlin's class renderer applies too.
 */
class OpenrndrDebuggerColors : CompoundRendererProvider() {

    override fun getName() = "OPENRNDR Color"

    override fun isEnabled() = true

    override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> = Function { type ->
        CompletableFuture.completedFuture(type is ClassType && isColorModel(type))
    }

    override fun getIconRenderer() = ValueIconRenderer { descriptor: ValueDescriptor, context: EvaluationContext, _: DescriptorLabelListener ->
        try {
            val value = descriptor.value as? ObjectReference ?: return@ValueIconRenderer null
            colorOf(value, context)?.let(::swatch)
        } catch (e: Exception) {
            throw EvaluateException(e.message, e)
        }
    }

    private fun colorOf(value: ObjectReference, context: EvaluationContext): Color? {
        val rgba = if (value.referenceType().name() == RGBA) {
            value
        } else {
            // Let the program convert its own color model; needs a thread suspended at a breakpoint
            val toRGBa = DebuggerUtils.findMethod(value.referenceType(), "toRGBa", null) ?: return null
            context.debugProcess.invokeMethod(context, value, toRGBa, emptyList()) as? ObjectReference ?: return null
        }
        val type = rgba.referenceType()
        fun component(name: String): Double? = type.fieldByName(name)?.let { (rgba.getValue(it) as? DoubleValue)?.value() }
        val r = component("r") ?: return null
        val g = component("g") ?: return null
        val b = component("b") ?: return null
        val alpha = component("alpha") ?: return null
        val linear = linearityName(rgba) == "LINEAR"
        fun screen(c: Double) = (if (linear) toSrgb(c) else c).toFloat().coerceIn(0f, 1f)
        return Color(screen(r), screen(g), screen(b), alpha.toFloat().coerceIn(0f, 1f))
    }

    /** The name of the `linearity` enum entry of a ColorRGBa (`LINEAR` or `SRGB`). */
    private fun linearityName(rgba: ObjectReference): String? {
        val linearity = rgba.getValue(rgba.referenceType().fieldByName("linearity") ?: return null) as? ObjectReference ?: return null
        val name = linearity.referenceType().fieldByName("name") ?: return null
        return (linearity.getValue(name) as? StringReference)?.value()
    }

    private companion object {
        const val RGBA = "org.openrndr.color.ColorRGBa"
        const val COLOR_MODEL = "org.openrndr.color.ColorModel"

        /** A class in OPENRNDR's color packages (including orx) that implements ColorModel. */
        fun isColorModel(type: ClassType): Boolean {
            val name = type.name()
            if (!OpenrndrColorEvaluator.COLOR_PACKAGES.any { name.startsWith("$it.") }) return false
            return type.allInterfaces().any { it.name() == COLOR_MODEL }
        }

        /** The sRGB transfer function OPENRNDR uses to go from linear to sRGB. */
        fun toSrgb(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055

        fun swatch(color: Color): Icon = JBUIScale.scaleIcon(ColorIcon(12, color, true))
    }
}
