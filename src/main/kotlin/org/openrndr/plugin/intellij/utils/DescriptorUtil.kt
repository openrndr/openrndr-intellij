package org.openrndr.plugin.intellij.utils

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.name.parentOrNull
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.plugin.intellij.editor.ConstantValueContainer
import org.openrndr.plugin.intellij.editor.ConstantValueContainer.Companion.getDefaultValueIfKnown

internal object DescriptorUtil {
    private fun ValueDescriptor.isColorModelShorthand(): Boolean {
        val s = containingDeclaration.getImportableDescriptor().fqNameSafe.asString()
        return s == "org.openrndr.color.rgb" || s == "org.openrndr.color.hsl" || s == "org.openrndr.color.hsv"
    }

    fun ValueParameterDescriptor.isAlpha(): Boolean =
        name.identifier == "alpha" || name.identifier == "a" && isColorModelShorthand()

    fun ValueParameterDescriptor.isRef(): Boolean = name.identifier == "ref"

    fun ValueParameterDescriptor.isLinearity(): Boolean = name.identifier == "linearity"

    private fun isColorModelPackage(s: String?): Boolean =
        s == "org.openrndr.color" || s == "org.openrndr.extra.color.presets" || s == "org.openrndr.extra.color.spaces"

    fun DeclarationDescriptor.isColorModelPackage(): Boolean =
        containingPackage()?.asString().let(::isColorModelPackage)

    fun ValueDescriptor.isColorModelPackage(): Boolean =
        type.fqName?.parentOrNull()?.asString().let(::isColorModelPackage)

    /**
     * Computes argument constants if it can, returns null otherwise.
     *
     * @return Either a mapping of all parameters to argument constants or null if any of the argument
     * constants cannot be computed.
     */
    fun ResolvedCall<out CallableDescriptor>.computeValueArguments(bindingContext: BindingContext): ArgumentMap? =
        buildMap {
            for ((parameter, argument) in valueArguments) {
                val expression = (argument as? ExpressionValueArgument)?.valueArgument?.getArgumentExpression()
                this[parameter] = if (expression == null) {
                    parameter.getDefaultValueIfKnown() ?: return null
                } else if (parameter.isRef()) {
                    val refContext = expression.analyze()
                    val refResolvedCall = expression.getResolvedCall(refContext) ?: return null
                    val importableDescriptor = refResolvedCall.resultingDescriptor.getImportableDescriptor()
                    val refColor = ColorUtil.staticWhitePointMap[importableDescriptor.name.identifier]
                        ?: refResolvedCall.computeValueArguments(refContext)?.computeWhitePoint() ?: return null
                    ConstantValueContainer.WhitePoint(refColor)
                } else {
                    val constant = ConstantExpressionEvaluator.getConstant(expression, bindingContext)
                        ?.toConstantValue(parameter.type) ?: return null
                    ConstantValueContainer.Constant(constant)
                }
            }
        }
}