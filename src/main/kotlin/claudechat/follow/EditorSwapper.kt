package claudechat.follow

import claudechat.git.canonicalPath
import com.intellij.ide.ui.UISettings
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

/** Swaps open editor tabs from one checkout to another (see [EditorSwapPlan]). EDT only. */
class EditorSwapper(private val project: Project, private val mapper: CheckoutMapper) {
    private val manager get() = FileEditorManager.getInstance(project)

    private data class Swap(val old: VirtualFile, val new: VirtualFile, val caret: Caret?)

    fun swap(from: Checkout, to: Checkout) {
        val files = manager.openFiles.toList()
        if (files.isEmpty()) return
        val byPath = files.associateBy { canonicalPath(it.path) }
        val local = LocalFileSystem.getInstance()
        // Counterparts are real files: refreshing finds ones the VFS has not seen yet. A file that lives in another
        // file system (the in-memory one of light tests) looks its counterpart up there.
        val other = files.firstOrNull { it.fileSystem !== local }?.fileSystem
        fun resolve(path: String): VirtualFile? = local.refreshAndFindFileByPath(path) ?: other?.findFileByPath(path)
        val open = files.map { OpenFile(it.path, caretOf(it)) }
        val swaps = EditorSwapPlan.plan(open, from, to, mapper) { resolve(it) != null }.mapNotNull { r ->
            val old = byPath[canonicalPath(r.from)] ?: return@mapNotNull null
            val new = resolve(r.to) ?: return@mapNotNull null
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
                // At the tab limit an extra tab would evict some other tab, so make room by closing the old one first.
                val closeFirst = window.fileList.size >= UISettings.getInstance().editorTabLimit && window.fileList.size > 1
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
