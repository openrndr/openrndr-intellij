package org.openrndr.plugin.intellij.debugger

import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.CompoundRendererProvider
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.ValueIconRenderer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.ColorIcon
import com.sun.jdi.*
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.function.Function

private val LOG = logger<ColorRGBaRendererProvider>()

internal class ColorRGBaRendererProvider : CompoundRendererProvider() {
    override fun getName(): String = "ColorRGBa"

    /**
     * The only way this gets called is when we specify our [CompoundRendererProvider] extension to be ordered first,
     * because when [com.intellij.debugger.engine.DebugProcessImpl.getAutoRendererAsync] is called, it will look for
     * the first applicable renderer in its list of enabled renderers and in the default ordering we are right
     * after KotlinClassRendererProvider which is applicable for ColorRGBa.
     */
    override fun getIconRenderer(): ValueIconRenderer {
        return ValueIconRenderer r@{ descriptor: ValueDescriptor, evaluationContext: EvaluationContext, listener: DescriptorLabelListener ->
            var objectReference = (descriptor.value as? ObjectReference) ?: return@r null
            var referenceType = objectReference.referenceType()
            val debugProcess = evaluationContext.debugProcess
            return@r try {
                // If it isn't ColorRGBa, we'll need to convert it to one
                if (referenceType.name() != CLASS_NAME) {
                    val toRGBa = DebuggerUtils.findMethod(referenceType, "toRGBa", null) ?: run {
                        LOG.error("Failed to find method \"toRGBa\" for $objectReference")
                        return@r null
                    }
                    objectReference = debugProcess.invokeMethod(
                        evaluationContext, objectReference, toRGBa, emptyList()
                    ) as? ObjectReference ?: return@r null
                    referenceType = objectReference.referenceType()
                }

                val (r, g, b, a) = listOf("getR", "getG", "getB", "getA").map {
                    val method = DebuggerUtils.findMethod(referenceType, it, null) ?: return@r null
                    val value = debugProcess.invokeMethod(
                        evaluationContext, objectReference, method, emptyList()
                    ) as? DoubleValue
                    value?.doubleValue()?.toFloat() ?: return@r null
                }
                return@r JBUIScale.scaleIcon(ColorIcon(16, 12, Color(r, g, b, a), true))
            } catch (e: EvaluateException) {
                LOG.error(e)
                null
            }
        }
    }

    override fun isEnabled(): Boolean = true

    override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> = Function { type: Type ->
        CompletableFuture.completedFuture(
            type is ClassType
                    && StringUtil.getPackageName(type.name()) == PACKAGE_NAME
                    && type.interfaces().any { it.name() == COLOR_MODEL_NAME }
        )
    }

    companion object {
        private const val PACKAGE_NAME = "org.openrndr.color"
        private const val COLOR_MODEL_NAME = "$PACKAGE_NAME.ColorModel"
        private const val CLASS_NAME = "$PACKAGE_NAME.ColorRGBa"
    }
}
