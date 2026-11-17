package org.openrndr.plugin.intellij

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Main toolbar group next to play/stop in OPENRNDR Mode: Reformat Code, Comment with Line Comment
 * and Rename. These are the platform's own actions, so they act on the editor exactly like their shortcuts;
 * Rename also falls back to the file being edited, see [SketchRename].
 */
class SketchEditGroup : ActionGroup(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = OpenrndrModeAction.isOn
    }

    private val children: Array<AnAction> by lazy {
        val am = ActionManager.getInstance()
        listOfNotNull(
            Separator.create(),
            am.getAction("ReformatCode"),
            am.getAction("CommentByLineComment"),
            am.getAction("RenameElement")?.let(::SketchRename),
        ).toTypedArray()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}

/**
 * The platform's Rename (Shift+F6): the symbol under the caret, or what is selected in the project view. A library
 * symbol such as `circle` still goes to the platform, which says it cannot be renamed. Where there is no symbol at
 * all (whitespace, brackets), it renames the file being edited instead, so the button is only grayed out when there
 * is no such file either. The tooltip names what a click will rename: "Rename Variable 'radius'", "Rename File
 * 'Spirals.kt'".
 */
internal class SketchRename(rename: AnAction) : AnActionWrapper(rename) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val presentation = e.presentation
        // Rename has no icon of its own, and a toolbar button needs one
        presentation.icon = AllIcons.Actions.Edit
        presentation.setText(templatePresentation.text, false)
        presentation.putClientProperty(FILE, null)
        val project = e.project ?: return
        if (presentation.isEnabled) {
            // No element: a renamer that does not work on PSI, such as resuming an in-place rename; left as it is
            val element = e.getData(CommonDataKeys.PSI_ELEMENT) ?: return
            presentation.setText(label(element), false)
            return
        }
        val file = fileToRename(
            project,
            focused = e.getData(CommonDataKeys.PSI_FILE),
            lastEdited = e.getData(PlatformDataKeys.LAST_ACTIVE_FILE_EDITOR)?.file,
        )
        presentation.isEnabled = file != null
        if (file != null) presentation.setText(label(file), false)
        // For actionPerformed, which runs on the EDT where the caret's element is not cheap to find again
        presentation.putClientProperty(FILE, file?.virtualFile)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val file = e.presentation.getClientProperty(FILE)
        if (project == null || file == null) return super.actionPerformed(e)
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        // What the platform's Rename File (editor tab menu) does
        PsiElementRenameHandler.invoke(psiFile, project, psiFile, null)
    }

    companion object {
        private val FILE = Key.create<VirtualFile>("openrndr.rename.file")

        /** Writable and not vetoed: the checks the platform's Rename makes only after it was clicked. */
        private fun canRename(project: Project, element: PsiElement): Boolean =
            !PsiElementRenameHandler.isVetoed(element) &&
                PsiElementRenameHandler.getRenameErrorMessage(project, null, element) == null

        /**
         * The file in the focused editor, else the one in the selected editor tab (focus on the console, say). Only
         * files of the project: the platform's checks let in-memory files through, such as the guide's tab.
         */
        internal fun fileToRename(project: Project, focused: PsiFile?, lastEdited: VirtualFile?): PsiFile? =
            sequence {
                yield(focused)
                yield(lastEdited?.takeIf { it.isValid }?.let { PsiManager.getInstance(project).findFile(it) })
            }.firstOrNull { file ->
                val vf = file?.virtualFile
                vf != null && ProjectFileIndex.getInstance(project).isInContent(vf) && canRename(project, file)
            }

        internal fun label(element: PsiElement): String {
            val kind = when (element) {
                is PsiDirectory -> "Folder"
                is PsiFile -> "File"
                else -> ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)
                    .ifBlank { "symbol" }
                    .let { StringUtil.capitalizeWords(it, true) }
            }
            val name = when (element) {
                is PsiDirectory -> element.name
                is PsiFile -> element.name
                else -> ElementDescriptionUtil.getElementDescription(element, UsageViewShortNameLocation.INSTANCE)
            }
            return "Rename $kind '$name'"
        }
    }
}
