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
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.Type
import org.openrndr.plugin.intellij.ui.RoundColorIcon
import java.awt.Color
import java.util.concurrent.CompletableFuture
import java.util.function.Function

@Suppress("UseJBColor")
class ColorRGBaRendererProvider : CompoundRendererProvider() {
    private companion object {
        val LOG = logger<ColorRGBaRendererProvider>()
        const val PACKAGE_NAME = "org.openrndr.color"
        const val COLOR_MODEL_NAME = "$PACKAGE_NAME.ColorModel"
        const val COLORRGBA_NAME = "$PACKAGE_NAME.ColorRGBa"
        val colorRGBaFieldNames = arrayOf("r", "g", "b", "alpha")
    }

    override fun getName(): String = "ColorRGBa"

    /**
     * The only way this gets called is when we specify our [CompoundRendererProvider] extension to be ordered first,
     * because when [com.intellij.debugger.engine.DebugProcessImpl.getAutoRendererAsync] is called, it will look for
     * the first applicable renderer in its list of enabled renderers and in the default ordering we are right
     * after KotlinClassRendererProvider which declares itself applicable for ColorRGBa.
     */
    override fun getIconRenderer(): ValueIconRenderer =
        ValueIconRenderer r@{ descriptor: ValueDescriptor, evaluationContext: EvaluationContext, _: DescriptorLabelListener ->
            try {
                var objectReference = (descriptor.value as? ObjectReference) ?: return@r null
                var referenceType = objectReference.referenceType()
                // If it isn't ColorRGBa, we'll need to convert it to one
                if (referenceType.name() != COLORRGBA_NAME) {
                    val toRGBa = DebuggerUtils.findMethod(referenceType, "toRGBa", null)
                        ?: return@r null.also { LOG.error("Failed to find method \"toRGBa\" for $objectReference") }
                    objectReference = evaluationContext.debugProcess.invokeMethod(
                        evaluationContext, objectReference, toRGBa, emptyList()
                    ) as? ObjectReference ?: return@r null
                    referenceType = objectReference.referenceType()
                }
                val fields = colorRGBaFieldNames.map { referenceType.fieldByName(it) ?: return@r null }
                // It just so happens that sorting the field names in descending order nets us the desired order
                val (r, g, b, alpha) = objectReference.getValues(fields).toList().sortedByDescending { it.first.name() }
                    .map { (it.second as? DoubleValue)?.floatValue() ?: return@r null }
                JBUIScale.scaleIcon(RoundColorIcon(Color(r, g, b, alpha), 16, 12))
            } catch (e: Exception) {
                throw EvaluateException(e.message, e)
            }
        }

    override fun isEnabled(): Boolean = true

    override fun getIsApplicableChecker(): Function<Type, CompletableFuture<Boolean>> = Function { type: Type? ->
        CompletableFuture.completedFuture(
            type is ClassType
                    && StringUtil.getPackageName(type.name()) == PACKAGE_NAME
                    && type.interfaces().any { it.name() == COLOR_MODEL_NAME }
        )
    }
}