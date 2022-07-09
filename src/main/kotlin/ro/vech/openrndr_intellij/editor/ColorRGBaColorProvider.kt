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
import org.jetbrains.kotlin.idea.refactoring.fqName.fqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getParameterForArgument
import org.jetbrains.kotlin.resolve.calls.components.hasDefaultValue
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.constants.CompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.EnumValue
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ConvertibleToColorRGBa
import org.openrndr.color.Linearity
import java.awt.Color
import kotlin.reflect.full.memberProperties

class ColorRGBaColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element !is LeafPsiElement) return null
        val parent = (element.parent as? KtNameReferenceExpression) ?: return null
        if (parent.parent !is KtCallExpression && parent.parent !is KtDotQualifiedExpression) return null

        val resolvedCall = parent.resolveToCall() ?: return null
        val descriptor = resolvedCall.resultingDescriptor
        val packageName = descriptor.containingPackage()?.asString()
        if (packageName != "org.openrndr.color" && packageName != "org.openrndr.extras.color.presets") return null

        val bindingContext = parent.analyze()
        val parametersToConstantsMap = resolvedCall.computeValueArguments(bindingContext) ?: return null

        val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor)
        return colorRGBaDescriptor?.colorFromArguments(parametersToConstantsMap)
            ?: staticColorMap[descriptor.getImportableDescriptor().name.identifier]
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
        /** Convenient way to get [Linearity] out of a resolved call. */
        fun ResolvedCall<out CallableDescriptor>.computeLinearity(bindingContext: BindingContext): Linearity? {
            val expressionValueArgument = valueArguments.firstNotNullOfOrNull { (parameter, argument) ->
                argument.takeIf { parameter.type.fqName?.asString() == "org.openrndr.color.Linearity" } as? ExpressionValueArgument
            }
            val argumentExpression = expressionValueArgument?.valueArgument?.getArgumentExpression() ?: return null
            val constant =
                ConstantExpressionEvaluator.getConstant(argumentExpression, bindingContext) as? TypedCompileTimeConstant
            val enum = constant?.constantValue as? EnumValue ?: return null
            return Linearity.valueOf(enum.enumEntryName.identifier)
        }

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

        /**
         * There isn't really a consistent naming scheme in OPENRNDR colors
         * but alpha is generally referred to by one of these two parameter names.
         * ColorLABa uses both parameter names, so we need a bespoke code path for it.
         */
        fun ValueParameterDescriptor.isAlpha(): Boolean {
            return if (containingDeclaration.getImportableDescriptor().name.asString() == "org.openrndr.color.ColorLABa") {
                name.identifier == "alpha"
            } else {
                name.identifier == "a" || name.identifier == "alpha"
            }
        }

        fun ConvertibleToColorRGBa.toAWTColor(): Color {
            val (r, g, b, a) = this as? ColorRGBa ?: toRGBa()
            return Color(
                (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), (a * 255).toInt()
            )
        }

        fun Color.toColorRGBa(): ColorRGBa {
            return ColorRGBa(
                red / 255.0, green / 255.0, blue / 255.0, alpha / 255.0
            )
        }

        val staticColorMap: Map<String, Color> = let {
            val map = mutableMapOf<String, Color>()
            for (property in ColorRGBa.Companion::class.memberProperties) {
                map[property.name] = (property.getter.call(ColorRGBa.Companion) as ColorRGBa).toAWTColor()
            }
            // There's no easy way to get the ColorRGBa extension properties in orx, we have to use Java reflection
            val extensionColorsJavaClass = Class.forName("org.openrndr.extras.color.presets.ColorsKt")
            for (method in extensionColorsJavaClass.declaredMethods) {
                // Every generated java method is prefixed with "get"
                map[method.name.drop(3)] =
                    (method.invoke(ColorRGBa::javaClass, ColorRGBa.Companion) as ColorRGBa).toAWTColor()
            }
            map
        }
    }
}
