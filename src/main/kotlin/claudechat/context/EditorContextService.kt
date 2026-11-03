package claudechat.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx
import com.intellij.openapi.editor.ex.FocusChangeListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import org.jetbrains.annotations.TestOnly
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows where the user is looking. The context editor is the text editor of this project that last had focus (focus
 * moving into the chat keeps it), or the selected text editor before any had focus; only editors of local files count.
 * Listeners hear, debounced, when the caret, selection, focused editor or selected tab changed. EDT only.
 */
@Service(Service.Level.PROJECT)
class EditorContextService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastFocusedRef: WeakReference<Editor>? = null
    private var lastFocused: Editor?
        get() = lastFocusedRef?.get()
        set(value) { lastFocusedRef = value?.let(::WeakReference) }

    /** The main checkout's folder, for counterpart paths (tests point it at the light project's content root). */
    var projectDir: String = project.basePath ?: System.getProperty("user.home")

    /** Runs a round of listener calls once the editor has been quiet for [DEBOUNCE_MS] (tests run it at once). */
    var schedule: (Runnable) -> Unit = { round ->
        alarm.cancelAllRequests()
        alarm.addRequest(round, DEBOUNCE_MS)
    }

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = changed(event.editor)
        }, this)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = changed(e.editor)
        }, this)
        (multicaster as? EditorEventMulticasterEx)?.addFocusChangeListener(object : FocusChangeListener {
            override fun focusGained(editor: Editor) = editorFocused(editor)
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                // A tab switch without focus (or v3 swapping editors to another checkout); a non-text tab means no context.
                lastFocused = (event.newEditor as? TextEditor)?.editor?.takeIf(::accepts)
                changed(null)
            }
        })
    }

    /** [editor] got focus: it becomes the context editor if it is a text editor of a local file in this project. */
    fun editorFocused(editor: Editor) {
        if (!accepts(editor)) return
        lastFocused = editor
        changed(editor)
    }

    /** [listener] runs on the EDT after the context may have changed. Removed when [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Where the user is looking, with the file's absolute path; null without a context editor. Commits the document. */
    @TestOnly
    fun capture(): EditorContext? {
        val editor = contextEditor() ?: return null
        val file = localFile(editor) ?: return null
        return locate(editor, file.path)
    }

    /** [capture] as the chat working in [cwd] names the file ([ContextPaths.forChat]); null when it has no name for it. */
    fun contextFor(cwd: String): EditorContext? {
        val editor = contextEditor() ?: return null
        val file = localFile(editor) ?: return null
        val path = ContextPaths.forChat(file.path, cwd, projectDir) { file.fileSystem.findFileByPath(it) != null } ?: return null
        return locate(editor, path)
    }

    private fun contextEditor(): Editor? =
        lastFocused?.takeIf(::accepts) ?: FileEditorManager.getInstance(project).selectedTextEditor?.takeIf(::accepts)

    private fun locate(editor: Editor, path: String): EditorContext {
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val offset = caret.offset
        val line = document.getLineNumber(offset)
        val selection = if (caret.hasSelection()) {
            val start = caret.selectionStart
            // A selection that ends at the start of a line stops on the line before it.
            val last = if (caret.selectionEnd > start) caret.selectionEnd - 1 else start
            Lines(document.getLineNumber(start) + 1, document.getLineNumber(last) + 1)
        } else {
            null
        }
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val (symbol, container) = runReadActionBlocking {
            documents.getPsiFile(document)?.let { SymbolFinder.find(it, document, offset) } ?: (null to null)
        }
        return EditorContext(path, line + 1, offset - document.getLineStartOffset(line) + 1, selection, symbol, container)
    }

    /** File tabs are MAIN_EDITOR; light-test editors are UNTYPED. Diff, console and preview editors never count. */
    private fun accepts(editor: Editor): Boolean =
        !editor.isDisposed && editor.project === project &&
            (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.UNTYPED) &&
            localFile(editor) != null

    private fun localFile(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isValid && it.isInLocalFileSystem }

    private fun changed(editor: Editor?) {
        if (editor != null && !accepts(editor)) return // consoles, diffs and previews never change the context
        if (listeners.isEmpty()) return
        schedule(Runnable { if (!project.isDisposed) listeners.forEach(::notify) })
    }

    private fun notify(listener: () -> Unit) {
        try {
            listener()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (e is ControlFlowException) throw e
            logger<EditorContextService>().warn("An editor-context listener failed", e)
        }
    }

    override fun dispose() = Unit

    companion object {
        const val DEBOUNCE_MS = 150

        fun getInstance(project: Project): EditorContextService = project.service()
    }
}
