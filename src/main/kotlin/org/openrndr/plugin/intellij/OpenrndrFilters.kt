package org.openrndr.plugin.intellij

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * The filters offered in `post(…)` and `blend(…)` inside `compose { }`: every concrete public subclass of OPENRNDR's `Filter` on the
 * module's classpath and in its sources. The `Filter1to1`s of orx-fx get a section per package (`blur`, `edges`, …),
 * everything else goes to Assorted.
 */
object OpenrndrFilters {
    const val FILTER = "org.openrndr.draw.Filter"
    const val FILTER_1TO1 = "org.openrndr.draw.Filter1to1"
    const val FILTER_2TO1 = "org.openrndr.draw.Filter2to1"
    private const val FX = "org.openrndr.extra.fx."
    /** orx-fx's blend modes (`Multiply`, `Screen`, …), listed first in `blend(…)`. */
    const val FX_BLEND = "org.openrndr.extra.fx.blend"
    /** The Layer calls whose first argument lists filters. */
    val FILTER_CALLS = setOf("post", "blend")
    private val BASES = setOf(FILTER, FILTER_1TO1, FILTER_2TO1, "org.openrndr.draw.Filter3to1")

    /** A filter class; [section] is its orx-fx package (`blur`) for `Filter1to1`s there, null for Assorted. */
    data class FilterClass(
        val qualifiedName: String,
        val name: String,
        val section: String?,
        val needsArguments: Boolean,
        val isOpen: Boolean,
        /** A `Filter2to1`, for `blend(…)`. */
        val is2to1: Boolean,
    ) {
        val packageName get() = qualifiedName.substringBeforeLast('.', "")
    }

    /**
     * The filters of [module], by qualified name. The libraries' are cached until the project's libraries change;
     * the module's own sources are searched each time.
     */
    fun filterClasses(module: Module): List<FilterClass> {
        val project = module.project
        val facade = JavaPsiFacade.getInstance(project)
        val everywhere = module.getModuleWithDependenciesAndLibrariesScope(false)
        val filter = facade.findClass(FILTER, everywhere) ?: return emptyList()
        // The cached provider looks its classes up itself: a cached value must not hold on to PSI
        val libraries = CachedValuesManager.getManager(project).getCachedValue(module) {
            val scope = module.getModuleWithDependenciesAndLibrariesScope(false)
            val base = JavaPsiFacade.getInstance(module.project).findClass(FILTER, scope)
            val found = base?.let {
                ClassInheritorsSearch.search(it, scope.intersectWith(ProjectScope.getLibrariesScope(module.project)), true).findAll()
                    .filter(::isOffered).map { cls -> describe(cls, scope) }
            }.orEmpty()
            CachedValueProvider.Result.create(found, ProjectRootModificationTracker.getInstance(module.project))
        }
        // A search limited to the sources does not pass through library classes, so the sources are searched for
        // subclasses of each open library filter (Filter1to1, …) as well
        val sourceScope = module.getModuleWithDependenciesScope()
        val bases = listOf(filter) + (BASES - FILTER + libraries.filter { it.isOpen }.map { it.qualifiedName }).mapNotNull { facade.findClass(it, everywhere) }
        val sources = bases.flatMap { base -> ClassInheritorsSearch.search(base, sourceScope, true).findAll() }
            .filter(::isOffered).map { describe(it, everywhere) }
        return (libraries + sources).distinctBy { it.qualifiedName }
    }

    private fun describe(cls: PsiClass, scope: GlobalSearchScope): FilterClass {
        val facade = JavaPsiFacade.getInstance(cls.project)
        val oneToOne = facade.findClass(FILTER_1TO1, scope)
        val twoToOne = facade.findClass(FILTER_2TO1, scope)
        val qualifiedName = cls.qualifiedName!!
        val packageName = qualifiedName.substringBeforeLast('.', "")
        val section = packageName.takeIf { it.startsWith(FX) && oneToOne != null && cls.isInheritor(oneToOne, true) }
            ?.removePrefix(FX)?.substringBefore('.')
        val is2to1 = twoToOne != null && cls.isInheritor(twoToOne, true)
        return FilterClass(qualifiedName, cls.name!!, section, needsArguments(cls), !cls.hasModifierProperty(PsiModifier.FINAL), is2to1)
    }

    /** Concrete, public, top-level classes, and not OPENRNDR's own base classes (`Filter1to1`, `Filter4to1`, …). */
    private fun isOffered(cls: PsiClass): Boolean {
        val qualifiedName = cls.qualifiedName ?: return false
        return !cls.isInterface && !cls.hasModifierProperty(PsiModifier.ABSTRACT) && cls.hasModifierProperty(PsiModifier.PUBLIC) &&
            cls.containingClass == null && qualifiedName !in BASES && !BASE_CLASS.matches(qualifiedName)
    }

    /** OPENRNDR's generic filter base classes: `Filter1to2`, `Filter2to2`, `Filter4to1`, … */
    private val BASE_CLASS = Regex("""org\.openrndr\.draw\.Filter\d+to\d+""")

    /** Whether no public constructor takes zero arguments (Kotlin adds one when all parameters have defaults). */
    private fun needsArguments(cls: PsiClass): Boolean {
        val constructors = cls.constructors.filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
        return constructors.isNotEmpty() && constructors.none { it.parameterList.isEmpty }
    }

    /** The qualified name of the class an item from the other contributors stands for (`BoxBlur()` → its class). */
    fun classOf(element: PsiElement?): String? = when (val target = element?.navigationElement ?: element) {
        is PsiClass -> target.qualifiedName
        is PsiMethod -> target.takeIf { it.isConstructor }?.containingClass?.qualifiedName
        is KtClassOrObject -> target.fqName?.asString()
        is KtConstructor<*> -> target.getContainingClassOrObject().fqName?.asString()
        else -> null
    }

    /** Names of the calls whose block has a Layer receiver: `compose { }`, `layer { }`, `aside { }`. */
    val LAYER_BLOCKS = setOf("compose", "layer", "aside")

    /** Whether the text before [offset] is `post` / `blend` in a `compose { }` / `layer { }` / `aside { }` block, from the PSI alone. */
    fun isPostCall(file: PsiFile, offset: Int): Boolean {
        val reference = PsiTreeUtil.getParentOfType(file.findElementAt((offset - 1).coerceAtLeast(0)), KtNameReferenceExpression::class.java)
            ?: return false
        return reference.getReferencedName() in FILTER_CALLS && reference.textRange.endOffset == offset && inLayerBlock(reference)
    }

    /** Whether [offset] is in the first argument of a `post(…)` / `blend(…)` in a layer block (`post(<caret>)`, `post(Bl<caret>)`), from the PSI alone. */
    fun isPostArgument(file: PsiFile, offset: Int): Boolean {
        val element = file.findElementAt(offset) ?: return false
        val list = PsiTreeUtil.getParentOfType(element, KtValueArgumentList::class.java) ?: return false
        val call = list.parent as? KtCallExpression ?: return false
        if ((call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() !in FILTER_CALLS) return false
        val first = list.arguments.firstOrNull()
        val inFirst = if (first == null) offset > list.textRange.startOffset else first.textRange.containsOffset(offset)
        return inFirst && inLayerBlock(call)
    }

    private fun inLayerBlock(element: PsiElement): Boolean {
        var lambda = PsiTreeUtil.getParentOfType(element, KtLambdaArgument::class.java)
        while (lambda != null) {
            val call = lambda.parent as? KtCallExpression
            if ((call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() in LAYER_BLOCKS) return true
            lambda = PsiTreeUtil.getParentOfType(lambda, KtLambdaArgument::class.java)
        }
        return false
    }
}

/** Opens the completion list on `post(` / `blend(` inside `compose { }`, where it lists the filters. */
class PostFilterTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (charTyped == '(' && file is KtFile && Openrndr.isProject(project) && OpenrndrFilters.isPostCall(file, editor.caretModel.offset)) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        }
        return Result.CONTINUE
    }
}
