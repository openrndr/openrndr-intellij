# ColorLineMarkerProvider: clicking a color swatch whose element was invalidated by an edit throws PsiInvalidElementAccessException

To file at https://youtrack.jetbrains.com/newIssue?project=IJPL (IntelliJ Platform).

**Build:** IntelliJ IDEA 2026.2 (IU-262.8665.258), JDK 25.0.3 (JetBrains), macOS. Seen in Kotlin (K2) files with a
third-party `ElementColorProvider`, but the code involved is language-independent.

## What happens

`ColorLineMarkerProvider.MyInfo` keeps a direct reference to the `PsiElement` it got from the
`ElementColorProvider`. Its navigation handler (`MyInfo.lambda$new$0`) uses that element without checking that it's
still valid. If you type in or near a color expression, the document commit's reparse can replace that element.
Clicking the swatch before the line markers are recalculated then throws:

```
com.intellij.psi.PsiInvalidElementAccessException: Invalid PSI Element: class com.intellij.psi.impl.source.tree.LeafPsiElement #kotlin  because: containing file is null; hierarchy=class org.jetbrains.kotlin.psi.KtNameReferenceExpression, class org.jetbrains.kotlin.psi.KtDotQualifiedExpression, …
	at com.intellij.psi.impl.source.tree.LeafPsiElement.invalid(LeafPsiElement.java:94)
	at com.intellij.psi.impl.source.tree.LeafPsiElement.getProject(LeafPsiElement.java:249)
	at com.intellij.ui.ColorLineMarkerProvider$MyInfo.lambda$new$0(ColorLineMarkerProvider.java:91)
	at com.intellij.codeInsight.daemon.NavigateAction.actionPerformed(NavigateAction.java:47)
	at com.intellij.openapi.editor.impl.EditorGutterComponentImpl.invokeGutterAction(EditorGutterComponentImpl.java:2575)
	at com.intellij.openapi.editor.impl.EditorGutterComponentImpl.mouseReleased(EditorGutterComponentImpl.java:2548)
	…
```

The invalidation trace shows the element was replaced by an ordinary typing commit:

```
	at com.intellij.psi.impl.DiffLog.doActualPsiChange(DiffLog.java:263)
	at com.intellij.psi.impl.DocumentCommitThread.doCommit$lambda$1(DocumentCommitThread.kt:492)
	at com.intellij.psi.impl.PsiDocumentManagerBase.commitDocument(PsiDocumentManagerBase.java:507)
	at org.jetbrains.kotlin.idea.editor.KotlinTypedHandler.charTyped(KotlinTypedHandler.kt:295)
	at com.intellij.codeInsight.editorActions.TypedHandler.doExecutePerCaret(TypedHandler.java:204)
	…
```

## Steps to reproduce

1. Have any `ElementColorProvider` that shows a swatch for an expression, for example a Kotlin call like
   `ColorRGBa.fromHex("#ff0000")`.
2. Type a character inside or right next to that expression, so the reparse replaces the swatch's element.
3. Click the gutter swatch before the daemon has re-run line markers.

The window is short, so it's easiest to hit when line marker computation is slow. It's a race, not a
deterministic failure.

**Expected:** the click does nothing, or opens the picker for the current element.

**Actual:** `PsiInvalidElementAccessException`, reported as an IDE internal error and blamed on the plugin that
provided the color.

## Cause

In `ColorLineMarkerProvider.MyInfo`'s navigation handler, the element is used straight away:

- line 84: `element.isWritable()`;
- line 86: `PsiEditorUtil.findEditor(element)`;
- line 91: `ColorChooserService.getInstance().showPopup(element.getProject(), …)`.

There is no `element.isValid()` check, and the element isn't held through a `SmartPsiElementPointer`. The listener
that later calls `ElementColorProvider.setColorTo(element, color)` captures the same raw element.

## Suggested fix

Return early from the handler when `!element.isValid()`, or keep a `SmartPsiElementPointer` in `MyInfo` and use the
restored element. Either way, the picker's `setColorTo` callback should check validity too.
   
## Attachments

`stacktrace.txt` and `invalidation.trace` from the IDE's attachments folder for this error. They include local file
paths, so check them before uploading.
