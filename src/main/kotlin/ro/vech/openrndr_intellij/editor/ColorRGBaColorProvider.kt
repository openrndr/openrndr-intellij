package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.command.CommandProcessor
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
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.getCall
import org.jetbrains.kotlin.resolve.calls.util.getParameterForArgument
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.ColorModel
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ColorXYZa
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.reflect.full.memberProperties

class ColorRGBaColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        val parent = (element.parent as? KtNameReferenceExpression) ?: return null
        val grandparent = parent.parent
        if (grandparent !is KtCallExpression && grandparent !is KtDotQualifiedExpression) return null

        val resolvedCall = parent.resolveToCall() ?: return null
        val descriptor = resolvedCall.resultingDescriptor
        val packageName = descriptor.containingPackage()?.asString()
        if (packageName != "org.openrndr.color" && packageName != "org.openrndr.extras.color.presets") return null

        if (grandparent is KtDotQualifiedExpression) {
            return staticColorMap[descriptor.getImportableDescriptor().name.identifier]
        }

        val bindingContext = parent.analyze()
        val parametersToConstantsMap = resolvedCall.computeValueArguments(bindingContext) ?: return null

        val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor)
        return colorRGBaDescriptor?.colorFromArguments(parametersToConstantsMap)
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val command = Runnable {
            val outerCallExpression = element.getStrictParentOfType<KtCallExpression>()
            val outerCallContext = outerCallExpression?.analyze() ?: return@Runnable
            val call = outerCallExpression.getCall(outerCallContext) ?: return@Runnable

            val resolvedCall = outerCallExpression.resolveToCall() ?: return@Runnable
            val targetDescriptor = resolvedCall.resultingDescriptor
            val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(targetDescriptor) ?: return@Runnable

            val colorArguments = colorRGBaDescriptor.argumentsFromColor(color)

            // If we can't find an existing alpha parameter, we'll need to add it ourselves
            val mustAddAlpha = call.valueArguments.none {
                resolvedCall.getParameterForArgument(it)?.isAlpha() ?: false
            }

            val psiFactory = KtPsiFactory(element)

            val newArgumentList = psiFactory.buildValueArgumentList {
                appendFixedText("(")
                call.valueArguments.forEachIndexed { i, argument ->
                    if (i > 0) {
                        appendFixedText(", ")
                    }
                    val parameter = resolvedCall.getParameterForArgument(argument) ?: return@forEachIndexed
                    if (argument.getArgumentName() != null) {
                        appendName(parameter.name)
                        appendFixedText(" = ")
                    }
                    // First 4 parameters of a color are always the color components
                    if (parameter.index <= 3) {
                        appendExpression(psiFactory.createExpression(colorArguments[parameter.index]))
                    } else {
                        appendExpression(argument.getArgumentExpression())
                    }
                }
                if (mustAddAlpha) {
                    val alphaParameter = resolvedCall.valueArguments.firstNotNullOfOrNull { (parameter, _) ->
                        parameter.takeIf { it.isAlpha() }
                    }
                    alphaParameter?.let {
                        appendFixedText(", ")
                        appendName(alphaParameter.name)
                        appendFixedText(" = ")
                        appendExpression(psiFactory.createExpression(colorArguments.last()))
                    }
                }
                appendFixedText(")")
            }
            outerCallExpression.getChildOfType<KtValueArgumentList>()?.replace(newArgumentList)
        }
        // TODO: Should use message bundle for command name
        CommandProcessor.getInstance().executeCommand(element.project, command, "Change Color", null, document)
    }

    internal companion object {
        /**
         * Computes argument constants if it can, computes to null for
         * missing arguments that have a default value in the parameter.
         */
        fun ResolvedCall<out CallableDescriptor>.computeValueArguments(bindingContext: BindingContext): Map<ValueParameterDescriptor, CompileTimeConstant<*>?>? {
            return valueArguments.map { (parameter, argument) ->
                val expression = (argument as? ExpressionValueArgument)?.valueArgument?.getArgumentExpression()
                if (!parameter.hasDefaultValue() && expression == null) return null
                val constant = expression?.let { ConstantExpressionEvaluator.getConstant(it, bindingContext) }
                parameter to constant
            }.toMap()
        }

        fun ValueParameterDescriptor.isAlpha(): Boolean {
            return containingDeclaration.getImportableDescriptor().name.asString().let {
                name.identifier == "alpha" || name.identifier == "a" && (it == "org.openrndr.color.rgb" || it == "org.openrndr.color.hsv")
            }
        }

        fun ColorModel<*>.toAWTColor(): Color = (this as? ColorRGBa ?: toRGBa()).clamped().run {
            Color(
                (r * 255.0).roundToInt(),
                (g * 255.0).roundToInt(),
                (b * 255.0).roundToInt(),
                (alpha * 255.0).roundToInt()
            )
        }

        private fun ColorRGBa.clamped(): ColorRGBa = (0.0..1.0).let {
            copy(r.coerceIn(it), g.coerceIn(it), b.coerceIn(it), alpha.coerceIn(it))
        }

        fun Color.toColorRGBa() = ColorRGBa(
            red / 255.0, green / 255.0, blue / 255.0, alpha / 255.0
        )

        /**
         * Uses a combination of Kotlin and Java reflection to create a String-to-Color mapping
         * of all static colors in openrndr.
         */
        // TODO: This could be generated at compile time instead.
        val staticColorMap: Map<String, Color> = buildMap {
            for (property in ColorRGBa.Companion::class.memberProperties) {
                this[property.name] = (property.getter.call(ColorRGBa.Companion) as ColorRGBa).toAWTColor()
            }
            // ColorXYZa white points
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
    }
}
