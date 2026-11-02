package org.openrndr.plugin.intellij

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.ThreeState
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * The `fileOrUrl` argument of OPENRNDR loaders (`loadImage("data/images/…")`, `loadFont("data/fonts/…", 64.0)`, …)
 * is a path relative to the project root, which is also the working directory sketches run in.
 * Like PyCharm does for `open()`, such paths get completion that pops up while typing and walks into
 * folders ([OpenrndrPathCompletion]), and Cmd+click navigation ([OpenrndrFileReferenceContributor]).
 */
object OpenrndrPaths {
    /** OPENRNDR / orx functions whose first parameter is `fileOrUrl`. */
    private val LOADERS = setOf(
        "loadImage", "loadImageSuspend", "loadFont", "loadFontImageMap", "loadFace",
        "loadCubemap", "loadBufferTexture", "loadVideo", "loadSVG",
    )

    /** The plain `"…"` literal [element] is in, if it is a loader's `fileOrUrl` argument in an OPENRNDR project. */
    fun pathLiteral(element: PsiElement?): KtStringTemplateExpression? {
        val literal = PsiTreeUtil.getParentOfType(element, KtStringTemplateExpression::class.java, false) ?: return null
        if (literal.hasInterpolation() || literal.text.startsWith("\"\"\"")) return null
        if (!isFileOrUrlArgument(literal) || !Openrndr.isProject(literal.project)) return null
        return literal
    }

    /** True for `loadX("…")` and `loadX(fileOrUrl = "…")` where loadX is one of [LOADERS]. */
    private fun isFileOrUrlArgument(literal: KtStringTemplateExpression): Boolean {
        val argument = literal.parent as? KtValueArgument ?: return false
        val call = (argument.parent as? KtValueArgumentList)?.parent as? KtCallExpression ?: return false
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
        if (callee !in LOADERS) return false
        val name = argument.getArgumentName()?.asName?.asString()
        return if (name != null) name == "fileOrUrl" else call.valueArguments.firstOrNull() == argument
    }

    fun root(project: Project): VirtualFile? = project.guessProjectDir()
}

/** Cmd+click navigation (and rename-aware paths) for loader paths. */
class OpenrndrFileReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(KtStringTemplateExpression::class.java), Provider)
    }

    private object Provider : PsiReferenceProvider() {
        override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
            val literal = OpenrndrPaths.pathLiteral(element) ?: return PsiReference.EMPTY_ARRAY
            val text = literal.text
            if (text.length < 2) return PsiReference.EMPTY_ARRAY
            val path = TextRange(1, text.length - 1).substring(text)
            if (path.contains("://")) return PsiReference.EMPTY_ARRAY
            return ProjectRootFileReferenceSet(path, literal).allReferences.map { it as PsiReference }.toTypedArray()
        }
    }

    /**
     * Resolves paths from the project root; soft, so a path that does not exist yet is not an error.
     * Its references offer no completion variants: [OpenrndrPathCompletion] does that.
     */
    private class ProjectRootFileReferenceSet(path: String, literal: KtStringTemplateExpression) :
        FileReferenceSet(path, literal, 1, null, true) {

        override fun computeDefaultContexts(): Collection<PsiFileSystemItem> {
            val root = OpenrndrPaths.root(element.project) ?: return emptyList()
            return listOfNotNull(PsiManager.getInstance(element.project).findDirectory(root))
        }

        override fun isSoft() = true

        override fun createFileReference(range: TextRange, index: Int, text: String): FileReference =
            object : FileReference(this, range, index, text) {
                override fun getVariants(): Array<Any> = emptyArray()
            }
    }
}

/**
 * Completion inside loader paths: the files and folders of the folder typed so far, folders first.
 * Picking a folder inserts `name/` and opens the list again for that folder.
 */
class OpenrndrPathCompletion : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val literal = OpenrndrPaths.pathLiteral(parameters.position) ?: return
        val root = OpenrndrPaths.root(literal.project) ?: return

        // What was typed between the opening quote and the caret, e.g. "data/ima"
        val contentStart = literal.textRange.startOffset + 1
        val typed = literal.containingFile.text.substring(contentStart, parameters.offset.coerceAtLeast(contentStart))
        if (typed.contains("://") || typed.startsWith("/")) return
        val folder = typed.substringBeforeLast('/', "")
        val dir = if (folder.isEmpty()) root else root.findFileByRelativePath(folder) ?: return

        val names = result.withPrefixMatcher(typed.substringAfterLast('/'))
        for (child in dir.children) {
            if (child.name.startsWith(".")) continue
            val item = LookupElementBuilder.create(child, if (child.isDirectory) "${child.name}/" else child.name)
                .withIcon(child.fileType.icon)
                .withInsertHandler { context, _ ->
                    // Walk on into the folder that was just picked
                    if (child.isDirectory) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
                }
            names.addElement(PrioritizedLookupElement.withPriority(item, if (child.isDirectory) 1.0 else 0.0))
        }
        result.stopHere()
    }
}

/** Opens the path completion as soon as something is typed inside a loader path, like PyCharm. */
class OpenrndrPathAutoPopup : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is KtFile || !(charTyped.isLetterOrDigit() || charTyped in "\"/._-")) return Result.CONTINUE
        // Checked after the character is in, once the opening quote has become a string literal
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor, CompletionType.BASIC) { psi ->
            OpenrndrPaths.pathLiteral(psi.findElementAt(editor.caretModel.offset)) != null
        }
        return Result.CONTINUE
    }
}

/** Kotlin normally keeps completion from popping up by itself inside strings; allow it for loader paths. */
class OpenrndrPathCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState =
        // Path literals, and the filter list in `post(…)` inside compose { }
        if (OpenrndrPaths.pathLiteral(contextElement) != null || OpenrndrFilters.isPostArgument(psiFile, offset)) ThreeState.NO else ThreeState.UNSURE
}
