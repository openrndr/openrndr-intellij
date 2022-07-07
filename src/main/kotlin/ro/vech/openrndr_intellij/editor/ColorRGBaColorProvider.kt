package ro.vech.openrndr_intellij.editor

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.descriptors.containingPackage
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.mapArgumentsToParameters
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getCall
import org.jetbrains.kotlin.resolve.constants.IntegerValueConstant
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstant
import org.jetbrains.kotlin.resolve.constants.TypedCompileTimeConstant
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.jetbrains.kotlin.types.TypeUtils
import org.openrndr.color.ColorRGBa
import org.openrndr.color.ConvertibleToColorRGBa
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
        val call = parent.getCall(bindingContext) ?: return null
        val argToParamMap = call.mapArgumentsToParameters(descriptor)
        val arguments = argToParamMap.getArgumentsInCanonicalOrder(bindingContext) ?: return null

        val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(descriptor)
        return colorRGBaDescriptor?.colorFromArguments(arguments)
            ?: staticColorMap[descriptor.getImportableDescriptor().name.asString()]
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        if (element !is LeafPsiElement) return
        val parent = (element.context as? KtNameReferenceExpression) ?: return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
        val command = Runnable {
            val resolvedCall = parent.resolveToCall() ?: return@Runnable
            val targetDescriptor = resolvedCall.resultingDescriptor
            val colorRGBaDescriptor = ColorRGBaDescriptor.fromCallableDescriptor(targetDescriptor) ?: return@Runnable
            val psiFactory = KtPsiFactory(element)

            // with credit to ConvertLambdaReferenceToIntention
            val outerCallExpression = parent.getStrictParentOfType<KtCallExpression>()
            val outerCallContext = outerCallExpression?.analyze() ?: return@Runnable

            val valueArguments = outerCallExpression.valueArguments

            val call = outerCallExpression.getCall(outerCallContext) ?: return@Runnable
            val argToParamMap = call.mapArgumentsToParameters(targetDescriptor)
            val colorArguments = colorRGBaDescriptor.argumentsFromColor(color)

            // If we get back more color arguments than we have value arguments, then
            // we won't attempt to hack them in alongside the existing named arguments,
            // instead we simply go back to all plain positional arguments
            val reuseNamedArguments = valueArguments.size == colorArguments.size

            val newArgumentList = psiFactory.buildValueArgumentList {
                appendFixedText("(")
                if (reuseNamedArguments) {
                    val sizeWithoutLast = valueArguments.size - 1
                    // These arguments are definitely in the right order
                    valueArguments.forEachIndexed { i, argument ->
                        // I don't think this could ever fail, but it's worth checking
                        val parameter = argToParamMap[argument] ?: return@forEachIndexed
                        if (argument.isNamed()) {
                            appendName(parameter.name)
                            appendFixedText(" = ")
                        }
                        appendExpression(psiFactory.createExpression(colorArguments[parameter.index]))
                        if (i < sizeWithoutLast) {
                            appendFixedText(", ")
                        }
                    }
                } else {
                    val sizeWithoutLast = colorArguments.size - 1
                    colorArguments.forEachIndexed { i, argument ->
                        appendExpression(psiFactory.createExpression(argument))
                        if (i < sizeWithoutLast) {
                            appendFixedText(", ")
                        }
                    }
                }
                appendFixedText(")")
            }
            parent.nextSibling.replace(newArgumentList)
        }
        // TODO: Should use message bundle for command name
        CommandProcessor.getInstance().executeCommand(element.project, command, "Change Color", null, document)
    }

    internal companion object {
        /** Resolves arguments to constants and returns them in canonical order. */
        fun Map<ValueArgument, ValueParameterDescriptor>.getArgumentsInCanonicalOrder(bindingContext: BindingContext): List<Any>? {
            return toList().sortedBy { it.second.index }.map {
                val expression = it.first.getArgumentExpression() ?: return null
                when (val constant = ConstantExpressionEvaluator.getConstant(expression, bindingContext)) {
                    is IntegerValueTypeConstant -> (constant.toConstantValue(TypeUtils.DONT_CARE) as? IntegerValueConstant)?.value
                    is TypedCompileTimeConstant -> constant.constantValue.value
                    else -> null
                } ?: return null
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
