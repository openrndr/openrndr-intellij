package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tower.NewAbstractResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.*
import java.awt.Color
import java.awt.color.ColorSpace
import kotlin.reflect.full.memberProperties

private val LOG = logger<ColorRGBaColorProvider>()

class ColorRGBaColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        val outerExpression = (element.parent as? KtNameReferenceExpression) ?: return null
        // TODO: Does the following actually help with performance?
        outerExpression.parent?.takeIf { it is KtCallExpression || it is KtDotQualifiedExpression } ?: return null

        val outerExpressionContext = outerExpression.analyze()
        // TODO: orx-color references always fail resolveToCall?
        val resolvedCall =
            outerExpression.getResolvedCall(outerExpressionContext) as? NewAbstractResolvedCall ?: return null
        val descriptor = resolvedCall.resultingDescriptor

        if (!descriptor.isColorModelPackage()) return null

        if (resolvedCall.kotlinCall?.callKind == KotlinCallKind.VARIABLE) {
            return staticColorMap[descriptor.getImportableDescriptor().name.identifier]
        }

        val argumentMap = resolvedCall.computeValueArguments(outerExpressionContext) ?: return null

        val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor)
        return colorRGBaDescriptor?.colorFromArguments(argumentMap)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val command = Runnable {
            val outerCallExpression = element.getStrictParentOfType<KtCallExpression>()
            val outerCallContext = outerCallExpression?.analyze() ?: return@Runnable
            val call = outerCallExpression.getCall(outerCallContext) ?: return@Runnable
            val resolvedCall = call.getResolvedCall(outerCallContext) as? NewAbstractResolvedCall ?: return@Runnable

            val descriptor = resolvedCall.resultingDescriptor
            val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor) ?: return@Runnable
            val argumentMap = resolvedCall.computeValueArguments(outerCallContext)
            val refArgumentPair = argumentMap?.firstNotNullOfOrNull { it.takeIf { (param, _) -> param.isRef() } }

            /**
             * This will always be null for color models which don't implement [ReferenceWhitePoint]
             * and always non-null for color models that do.
             */
            val ref = (refArgumentPair?.value as? ConstantValueContainer.WhitePoint)?.value
            val colorArguments = colorRGBaDescriptor.argumentsFromColor(color, ref)

            outerCallExpression.getChildOfType<KtValueArgumentList>()?.let {
                it.replace(it.constructReplacement(resolvedCall, colorArguments))
            }
        }
        // TODO: Should use message bundle for command name
        CommandProcessor.getInstance().executeCommand(element.project, command, "Change Color", null, document)
    }

    internal companion object {
        /**
         * Computes argument constants if it can, returns null otherwise.
         *
         * @return Either a mapping of parameters to argument constants or null if any of the argument
         * constants cannot be computed.
         */
        fun ResolvedCall<out CallableDescriptor>.computeValueArguments(bindingContext: BindingContext): ArgumentMap? {
            return buildMap {
                for ((parameter, argument) in valueArguments) {
                    val expression = (argument as? ExpressionValueArgument)?.valueArgument?.getArgumentExpression()
                    if (expression == null && parameter.hasDefaultValue()) {
                        when {
                            parameter.isAlpha() -> set(parameter, ConstantValueContainer.ALPHA)
                            parameter.isRef() -> set(parameter, ConstantValueContainer.REF)
                            parameter.isLinearity() -> set(parameter, ConstantValueContainer.LINEARITY)
                        }
                        continue
                    }
                    expression?.let {
                        if (parameter.isRef()) {
                            val refResolvedCall = expression.resolveToCall() ?: return null
                            // TODO: Handle white points which aren't static
                            val refColor =
                                staticWhitePointMap[refResolvedCall.resultingDescriptor.getImportableDescriptor().name.identifier]
                                    ?: return null
                            set(parameter, ConstantValueContainer.WhitePoint(refColor))
                        } else {
                            val constant = ConstantExpressionEvaluator.getConstant(it, bindingContext)
                                ?.toConstantValue(parameter.type) ?: return null
                            set(parameter, ConstantValueContainer.Constant(constant))
                        }
                    }
                }
            }
        }

        /**
         * Non-destructively builds and returns a new [KtValueArgumentList].
         *
         * @param replacementArguments Replacement arguments which are
         * retrieved by parameter index and converted into expressions
         */
        fun KtValueArgumentList.constructReplacement(
            resolvedCall: ResolvedCall<out CallableDescriptor>, replacementArguments: Array<String>
        ): KtValueArgumentList {
            val psiFactory = KtPsiFactory(this)
            val resolvedArgumentMap = resolvedCall.valueArguments
            val firstValueArgument = arguments.first()
            return psiFactory.buildValueArgumentList {
                appendFixedText("(")
                for ((parameter, argument) in resolvedArgumentMap) {
                    val newArgument = replacementArguments.getOrNull(parameter.index)
                    when (argument) {
                        is ExpressionValueArgument -> {
                            val valueArgument = argument.valueArgument!!
                            if (valueArgument != firstValueArgument) appendFixedText(", ")
                            valueArgument.getArgumentName()?.asName?.let {
                                appendName(it)
                                appendFixedText(" = ")
                            }
                            appendExpression(
                                newArgument?.let(psiFactory::createExpression) ?: valueArgument.getArgumentExpression()
                            )
                        }
                        is DefaultValueArgument -> {
                            newArgument?.let {
                                if (parameter.index > 0) appendFixedText(", ")
                                appendName(parameter.name)
                                appendFixedText(" = ")
                                appendExpression(psiFactory.createExpression(it))
                            }
                        }
                        else -> LOG.error("Parameter $parameter has unhandled argument $argument")
                    }
                }
                appendFixedText(")")
            }
        }

        fun CallableDescriptor.isColorModelPackage() = containingPackage()?.asString()?.let {
            it == "org.openrndr.color" || it == "org.openrndr.extras.color.presets" || it == "org.openrndr.extra.color.spaces"
        } ?: false

        fun ValueParameterDescriptor.isAlpha(): Boolean {
            return containingDeclaration.getImportableDescriptor().fqNameSafe.asString().let {
                name.identifier == "alpha" || name.identifier == "a" && (it == "org.openrndr.color.rgb" || it == "org.openrndr.color.hsl" || it == "org.openrndr.color.hsv")
            }
        }

        fun ValueParameterDescriptor.isRef(): Boolean = name.identifier == "ref"

        fun ValueParameterDescriptor.isLinearity(): Boolean = name.identifier == "linearity"

        fun ColorModel<*>.toAWTColor(): Color = toRGBa().clamped().run {
            val colorSpace = when (linearity) {
                Linearity.LINEAR, Linearity.ASSUMED_LINEAR -> ColorSpace.CS_LINEAR_RGB
                Linearity.SRGB, Linearity.ASSUMED_SRGB, Linearity.UNKNOWN -> ColorSpace.CS_sRGB
            }.let { ColorSpace.getInstance(it) }
            Color(colorSpace, floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat()), alpha.toFloat())
        }

        private fun ColorRGBa.clamped(): ColorRGBa = (0.0..1.0).let {
            copy(r.coerceIn(it), g.coerceIn(it), b.coerceIn(it), alpha.coerceIn(it))
        }

        fun Color.toColorRGBa(): ColorRGBa {
            val linearity = when (colorSpace.type) {
                ColorSpace.CS_LINEAR_RGB -> Linearity.LINEAR
                ColorSpace.CS_sRGB -> Linearity.SRGB
                else -> Linearity.UNKNOWN
            }
            return getComponents(null).let { (r, g, b, a) ->
                ColorRGBa(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble(), linearity)
            }
        }

        /**
         * Uses a combination of Kotlin and Java reflection to create a String-to-Color mapping
         * of all static colors in openrndr.
         */
        val staticColorMap: Map<String, Color> = buildMap {
            // Standard ColorRGBa static colors
            for (property in ColorRGBa.Companion::class.memberProperties) {
                this[property.name] = (property.getter.call(ColorRGBa.Companion) as ColorRGBa).toAWTColor()
            }
            // ColorXYZa static white points
            for (property in ColorXYZa.Companion::class.memberProperties) {
                this[property.name] = (property.getter.call(ColorXYZa.Companion) as ColorXYZa).toAWTColor()
            }
            // There's no easy way to get the ColorRGBa extension properties in orx, we have to use Java reflection
            val extensionColorsJavaClass = Class.forName("org.openrndr.extras.color.presets.ColorsKt")
            for (method in extensionColorsJavaClass.declaredMethods) {
                // Every generated java method is prefixed with "get"
                this[method.name.drop(3)] =
                    (method.invoke(ColorRGBa::javaClass, ColorRGBa.Companion) as ColorRGBa).toAWTColor()
            }
        }

        val staticWhitePointMap: Map<String, ColorXYZa> = buildMap {
            // ColorXYZa static white points
            for (property in ColorXYZa.Companion::class.memberProperties) {
                this[property.name] = property.getter.call(ColorXYZa.Companion) as ColorXYZa
            }
        }
    }
}
