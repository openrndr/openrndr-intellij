package org.openrndr.plugin.intellij

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Warns about common mistakes in the structure of an OPENRNDR program, each with a quick fix that moves the
 * statement where it belongs (offered only when that move is safe):
 * - `extend { }` inside `extend { }`: adds a new extension every frame;
 * - `configure { }` inside `program { }`: runs after the window is created, so it has no effect;
 * - loading images, fonts or other GPU resources in `application { }` outside `program { }`: there is no
 *   graphics context yet;
 * - loading images, fonts or videos inside `extend { }`: they are loaded again every frame.
 *
 * Only the nesting of calls with trailing lambdas is looked at, so it is cheap and also works while indexing.
 */
class OpenrndrStructureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (!Openrndr.isProject(holder.project) && !ApplicationManager.getApplication().isUnitTestMode) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : KtVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
                val mistake = Mistake.entries.firstOrNull { it.matches(expression, callee.getReferencedName()) } ?: return
                val fixes = if (mistake.plan(expression) != null) arrayOf<LocalQuickFix>(MoveFix(mistake)) else LocalQuickFix.EMPTY_ARRAY
                holder.registerProblem(callee, mistake.message(callee.getReferencedName()), *fixes)
            }
        }
    }

    /** Moving [statement] into [block], before [anchor] (or at the end of the block when [anchor] is null). */
    private class Move(val statement: KtExpression, val block: KtBlockExpression, val anchor: PsiElement?)

    private enum class Mistake(val fixName: String) {
        NESTED_EXTEND("Move out of the outer extend { }") {
            override fun matches(call: KtCallExpression, name: String) = name == "extend" && "extend" in enclosingBlocks(call)
            override fun message(name: String) =
                "extend { } inside extend { } adds a new extension every frame; move it out of the outer extend { }"

            // Just before the outer extend { }
            override fun plan(call: KtCallExpression): Move? {
                val outer = ownerLambda(call, "extend") ?: return null
                if (directStatement(call, outer) != call) return null
                return moveBefore(call, ownerCall(outer) ?: return null)
            }
        },

        CONFIGURE_IN_PROGRAM("Move to application { }") {
            override fun matches(call: KtCallExpression, name: String) =
                name == "configure" && call.lambdaArguments.isNotEmpty() && enclosingBlocks(call).takeWhile { it != "application" }.contains("program")
            override fun message(name: String) =
                "configure { } inside program { } has no effect: the window already exists. Move it into application { }, before program { }"

            // Just before program { }, in application { }
            override fun plan(call: KtCallExpression): Move? {
                val program = ownerLambda(call, "program") ?: return null
                if (directStatement(call, program) != call) return null
                val programCall = ownerCall(program) ?: return null
                val application = ownerLambda(programCall, "application") ?: return null
                val block = application.bodyExpression ?: return null
                return Move(call, block, directStatement(programCall, application) ?: return null)
            }
        },

        GPU_OUTSIDE_PROGRAM("Move into program { }") {
            override fun matches(call: KtCallExpression, name: String) =
                name in GPU_RESOURCES && enclosingBlocks(call).takeWhile { it != "program" }.contains("application")
            override fun message(name: String) =
                "$name needs a graphics context, which only exists inside program { }; move it into program { }"

            // At the top of program { }, after statements moved there before, so their order is kept
            override fun plan(call: KtCallExpression): Move? {
                val application = ownerLambda(call, "application") ?: return null
                val statement = directStatement(call, application) ?: return null
                if (callName(statement) == "configure" || containsCall(statement, "program")) return null
                val appBlock = application.bodyExpression ?: return null
                val program = appBlock.statements.firstNotNullOfOrNull { programLambda(it) } ?: return null
                val block = program.bodyExpression ?: return null
                val anchor = block.statements.firstOrNull { !loadsResource(it) }
                return Move(statement, block, anchor)
            }
        },

        LOADING_EVERY_FRAME("Load once before extend { }") {
            override fun matches(call: KtCallExpression, name: String) =
                name in LOADERS && enclosingBlocks(call).takeWhile { it != "program" }.contains("extend")
            override fun message(name: String) =
                "$name inside extend { } loads it again every frame; load it once in program { }, before extend { }"

            // A `val x = load…(…)` straight in extend { } moves just before extend { }, unless it uses a value
            // declared earlier in the same frame (then moving it would change what is loaded)
            override fun plan(call: KtCallExpression): Move? {
                val extend = ownerLambda(call, "extend") ?: return null
                val property = directStatement(call, extend) as? KtProperty ?: return null
                val block = extend.bodyExpression ?: return null
                val earlier = block.statements.takeWhile { it != property }.filterIsInstance<KtDeclaration>().mapNotNull { it.name }.toSet()
                val used = PsiTreeUtil.findChildrenOfType(property.initializer, KtNameReferenceExpression::class.java).map { it.getReferencedName() }
                if (used.any { it in earlier }) return null
                return moveBefore(property, ownerCall(extend) ?: return null)
            }
        };

        abstract fun matches(call: KtCallExpression, name: String): Boolean
        abstract fun message(name: String): String
        abstract fun plan(call: KtCallExpression): Move?
    }

    private class MoveFix(private val mistake: Mistake) : LocalQuickFix {
        override fun getFamilyName() = mistake.fixName

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val call = descriptor.psiElement?.parent as? KtCallExpression ?: return
            val move = mistake.plan(call) ?: return
            val added = if (move.anchor != null) move.block.addBefore(move.statement.copy(), move.anchor)
            else move.block.addBefore(move.statement.copy(), move.block.rBrace ?: return)
            move.block.addAfter(KtPsiFactory(project).createNewLine(), added)
            // Remove the statement with the line break in front of it, so no blank line is left behind
            (move.statement.prevSibling as? PsiWhiteSpace)?.delete()
            move.statement.delete()
            CodeStyleManager.getInstance(project).reformat(added)
        }
    }

    private companion object {
        /** Functions that create GPU resources, which need the window's graphics context. */
        val GPU_RESOURCES = setOf(
            "loadImage", "loadImageSuspend", "loadFont", "loadFontImageMap", "loadCubemap", "loadBufferTexture",
            "loadVideo", "colorBuffer", "depthBuffer", "renderTarget", "vertexBuffer", "indexBuffer",
            "arrayTexture", "arrayCubemap", "cubemap", "volumeTexture", "bufferTexture", "shaderStorageBuffer",
        )

        /** Functions that load a file; slow, so not something to do every frame. */
        val LOADERS = setOf(
            "loadImage", "loadImageSuspend", "loadFont", "loadFontImageMap", "loadCubemap", "loadBufferTexture",
            "loadVideo", "loadSVG", "loadComposition",
        )

        /** The names of the calls whose trailing lambdas contain [element], innermost first (`extend`, `program`, `application`). */
        fun enclosingBlocks(element: PsiElement): List<String> = generateSequence(element.parent) { it.parent }
            .filterIsInstance<KtLambdaExpression>()
            .mapNotNull { lambdaOwner(it) }
            .toList()

        /** `extend` for the lambda of `extend { … }` or `extend(Screenshots()) { … }`. */
        fun lambdaOwner(lambda: KtLambdaExpression): String? =
            (ownerCall(lambda)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

        /** The call a lambda is passed to. */
        fun ownerCall(lambda: KtLambdaExpression): KtCallExpression? {
            val argument = lambda.parent as? KtValueArgument ?: return null
            return (argument as? KtLambdaArgument)?.parent as? KtCallExpression
                ?: (argument.parent as? KtValueArgumentList)?.parent as? KtCallExpression
        }

        /** The nearest lambda around [element] that is passed to a call named [name]. */
        fun ownerLambda(element: PsiElement, name: String): KtLambdaExpression? =
            generateSequence(element.parent) { it.parent }.filterIsInstance<KtLambdaExpression>().firstOrNull { lambdaOwner(it) == name }

        /** The statement of [lambda]'s body that contains [element]. */
        fun directStatement(element: PsiElement, lambda: KtLambdaExpression): KtExpression? {
            val block = lambda.bodyExpression ?: return null
            return generateSequence(element) { it.parent }.takeWhile { it != block }.lastOrNull() as? KtExpression
        }

        /** Moving [statement] just before the statement that contains [target], in the block around it. */
        fun moveBefore(statement: KtExpression, target: KtCallExpression): Move? {
            val targetStatement = generateSequence<PsiElement>(target) { it.parent }.firstOrNull { it.parent is KtBlockExpression } ?: return null
            return Move(statement, targetStatement.parent as KtBlockExpression, targetStatement)
        }

        fun callName(statement: KtExpression): String? =
            ((statement as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

        fun containsCall(element: PsiElement, name: String) =
            PsiTreeUtil.findChildrenOfType(element, KtCallExpression::class.java).any { callName(it) == name }

        /** The lambda of `program { }` when [statement] is that call. */
        fun programLambda(statement: KtExpression): KtLambdaExpression? =
            (statement as? KtCallExpression)?.takeIf { callName(it) == "program" }?.lambdaArguments?.firstOrNull()?.getLambdaExpression()

        /** A statement that loads or creates a GPU resource, such as one moved into program { } before. */
        fun loadsResource(statement: KtExpression): Boolean =
            (statement is KtCallExpression || statement is KtProperty) &&
                (sequenceOf(statement) + PsiTreeUtil.findChildrenOfType(statement, KtCallExpression::class.java))
                    .filterIsInstance<KtCallExpression>().any { callName(it) in GPU_RESOURCES }
    }
}
