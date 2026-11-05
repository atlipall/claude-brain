package tether.follow

import tether.git.canonicalPath
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Swaps open editor tabs from one checkout to another (see [EditorSwapPlan]). EDT only, in three phases: on the EDT
 * the open tabs and carets are snapshotted; on a pooled thread every candidate counterpart is looked up (a VFS refresh,
 * which must not run on the EDT or inside a read action); back on the EDT the planner runs on those results alone and
 * the plan is applied. The three seams exist for tests.
 */
class EditorSwapper(
    private val project: Project,
    private val mapper: CheckoutMapper,
    /** Runs the lookup phase; off the EDT. */
    private val runInBackground: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    /** Runs the apply phase; on the EDT. */
    private val runOnEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it, project.disposed) },
    /** Finds the file at a path, refreshing the VFS so files it has not seen yet are found. Background phase only. */
    private val lookup: (String) -> VirtualFile? = { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) },
) {
    private val manager get() = FileEditorManager.getInstance(project)

    private data class Swap(val old: VirtualFile, val new: VirtualFile, val caret: Caret?)

    /**
     * Swaps the tabs of [from] for their counterparts in [to]; the result is dropped when [isCurrent] is false by then.
     * [onDone] runs on the EDT once the swap is applied, dropped or found to be empty. A file that lives in another
     * file system (the in-memory one of light tests) has its counterpart looked up there.
     */
    fun swap(from: Checkout, to: Checkout, isCurrent: () -> Boolean = { true }, onDone: () -> Unit = {}) {
        val files = manager.openFiles.toList()
        if (files.isEmpty()) return onDone()
        val carets = files.associateWith { caretOf(it) }
        val candidates = files.mapNotNull { mapper.counterpart(it.path, from, to) }.distinct()
        if (candidates.isEmpty()) return onDone()
        val otherFileSystem = files.firstOrNull { it.fileSystem !== LocalFileSystem.getInstance() }?.fileSystem
        runInBackground {
            val resolved: Map<String, VirtualFile?> = candidates.associateWith { lookup(it) ?: otherFileSystem?.findFileByPath(it) }
            runOnEdt {
                if (!project.isDisposed && isCurrent()) apply(from, to, resolved, carets)
                onDone()
            }
        }
    }

    /** EDT: plan with the looked-up [resolved] files (no VFS access) against the tabs open now, and carry it out. */
    private fun apply(from: Checkout, to: Checkout, resolved: Map<String, VirtualFile?>, carets: Map<VirtualFile, Caret?>) {
        val files = manager.openFiles.toList()
        val byPath = files.associateBy { canonicalPath(it.path) }
        val open = files.map { OpenFile(it.path, carets[it]) }
        val swaps = EditorSwapPlan.plan(open, from, to, mapper) { resolved[it] != null }.mapNotNull { r ->
            val old = byPath[canonicalPath(r.from)] ?: return@mapNotNull null
            val new = resolved[r.to]?.takeIf { it.isValid } ?: return@mapNotNull null
            Swap(old, new, r.caret)
        }
        if (swaps.isEmpty()) return
        saveOldDocuments(swaps)
        val ex = manager as? FileEditorManagerEx
        val windows = ex?.windows.orEmpty()
        if (ex == null || windows.isEmpty()) swapWithoutWindows(swaps) else swapInWindows(ex, windows.toList(), swaps)
    }

    /** Saves the files about to be closed: a closed editor's unsaved changes stay in memory, waiting behind a tab that is gone. */
    private fun saveOldDocuments(swaps: List<Swap>) {
        val documents = FileDocumentManager.getInstance()
        swaps.forEach { s -> documents.getCachedDocument(s.old)?.let(documents::saveDocument) }
    }

    /**
     * In each editor split: select the old tab, open the counterpart in that split (a new tab opens right after the
     * selected one unless "Open new tabs at the end" is on), close the old tab, then restore the split's selection.
     * At the tab limit the old tab is closed before the counterpart opens, as an extra tab would evict some other tab.
     */
    private fun swapInWindows(ex: FileEditorManagerEx, windows: List<EditorWindow>, swaps: List<Swap>) {
        val current = ex.currentWindow
        for (window in windows) {
            val selected = window.selectedFile
            var reselect = selected
            for (s in swaps) {
                if (s.old !in window.fileList) continue
                window.setSelectedComposite(s.old, false)
                val request = FileEditorOpenRequest(targetWindow = window, selectAsCurrent = false, requestFocus = false)
                val closeFirst = atTabLimit(window)
                if (closeFirst) window.closeFile(s.old)
                val editors = ex.openFile(s.new, request).allEditors
                if (!closeFirst) window.closeFile(s.old)
                placeCaret(s.new, editors, s.caret)
                if (s.old == selected) reselect = s.new
            }
            reselect?.let { window.setSelectedComposite(it, false) }
        }
        current?.let { ex.currentWindow = it }
    }

    /** Whether opening one more tab in [window] would exceed the editor tab limit (never true for a lone tab). */
    private fun atTabLimit(window: EditorWindow): Boolean =
        window.fileList.size >= UISettings.getInstance().editorTabLimit && window.fileList.size > 1

    /** No editor splits (light tests): open the counterpart, close the old file, keep the selected file selected. */
    private fun swapWithoutWindows(swaps: List<Swap>) {
        val selected = manager.selectedFiles.firstOrNull()
        var reselect: VirtualFile? = null
        for (s in swaps) {
            val editors = manager.openFile(s.new, false)
            manager.closeFile(s.old)
            placeCaret(s.new, editors.toList(), s.caret)
            if (s.old == selected) reselect = s.new
        }
        reselect?.let { manager.openFile(it, true) }
    }

    private fun caretOf(file: VirtualFile): Caret? =
        (manager.getSelectedEditor(file) as? TextEditor)?.editor?.let { e ->
            val line = e.caretModel.logicalPosition.line
            Caret(line, e.caretModel.offset - e.document.getLineStartOffset(line))
        }

    private fun placeCaret(file: VirtualFile, editors: List<FileEditor>, caret: Caret?) {
        caret ?: return
        val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
            ?: (manager.getSelectedEditor(file) as? TextEditor)?.editor ?: return
        val document = editor.document
        val at = caret.clamp(document.lineCount) { document.getLineEndOffset(it) - document.getLineStartOffset(it) }
        editor.caretModel.moveToOffset(document.getLineStartOffset(at.line) + at.column)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
}
