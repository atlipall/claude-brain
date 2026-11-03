package claudechat.follow

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Swaps open editor tabs from one checkout to another (see [EditorSwapPlan]). EDT only. */
class EditorSwapper(private val project: Project, private val mapper: CheckoutMapper) {
    private val manager get() = FileEditorManager.getInstance(project)

    private data class Swap(val old: VirtualFile, val new: VirtualFile, val caret: Caret?)

    fun swap(from: Checkout, to: Checkout) {
        val files = manager.openFiles.toList()
        if (files.isEmpty()) return
        val byPath = files.associateBy { it.path }
        val fs = files.first().fileSystem // the local file system; an in-memory one in light tests
        val open = files.map { OpenFile(it.path, caretOf(it)) }
        val swaps = EditorSwapPlan.plan(open, from, to, mapper) { fs.findFileByPath(it) != null }.mapNotNull { r ->
            val old = byPath[r.from] ?: return@mapNotNull null
            val new = fs.findFileByPath(r.to) ?: return@mapNotNull null
            Swap(old, new, r.caret)
        }
        if (swaps.isEmpty()) return
        // A closed editor's unsaved changes stay in memory; saving first means none wait behind a tab that is gone.
        val documents = FileDocumentManager.getInstance()
        swaps.forEach { s -> documents.getCachedDocument(s.old)?.let(documents::saveDocument) }
        val ex = manager as? FileEditorManagerEx
        val windows = ex?.windows.orEmpty()
        if (ex == null || windows.isEmpty()) swapWithoutWindows(swaps) else swapInWindows(ex, windows.toList(), swaps)
    }

    /**
     * In each editor split: select the old tab, open the counterpart in that split (a new tab opens right after the
     * selected one unless "Open new tabs at the end" is on), close the old tab, then restore the split's selection.
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
                val editors = ex.openFile(s.new, request).allEditors
                window.closeFile(s.old)
                placeCaret(s.new, editors, s.caret)
                if (s.old == selected) reselect = s.new
            }
            reselect?.let { window.setSelectedComposite(it, false) }
        }
        current?.let { ex.currentWindow = it }
    }

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
        (manager.getSelectedEditor(file) as? TextEditor)?.editor?.caretModel?.logicalPosition?.let { Caret(it.line, it.column) }

    private fun placeCaret(file: VirtualFile, editors: List<FileEditor>, caret: Caret?) {
        caret ?: return
        val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
            ?: (manager.getSelectedEditor(file) as? TextEditor)?.editor ?: return
        val document = editor.document
        val at = caret.clamp(document.lineCount) { document.getLineEndOffset(it) - document.getLineStartOffset(it) }
        editor.caretModel.moveToLogicalPosition(LogicalPosition(at.line, at.column))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
}
