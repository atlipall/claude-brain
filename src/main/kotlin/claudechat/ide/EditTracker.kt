package claudechat.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Records project files the user saved from the IDE. Claude's own writes are disk changes that the IDE
 * reloads (never saves), so they are not recorded.
 */
@Service(Service.Level.PROJECT)
class EditTracker(private val project: Project) : Disposable {
    private val edited = LinkedHashSet<String>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    FileDocumentManager.getInstance().getFile(document)?.let(::record)
                }
            })
    }

    fun record(file: VirtualFile) {
        if (project.isDisposed) return
        val relative = ReadAction.compute<String?, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            if (!index.isInContent(file)) null
            else index.getContentRootForFile(file)?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        } ?: return
        synchronized(edited) { edited += relative }
    }

    /** Saves all documents (recording unsaved user edits), returns the note for the next message or null, and resets. EDT only. */
    fun consumeNote(): String? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val files = synchronized(edited) { edited.toList().also { edited.clear() } }
        if (files.isEmpty()) return null
        return "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\n" +
            "Re-read them before relying on earlier contents."
    }

    fun clear() = synchronized(edited) { edited.clear() }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): EditTracker = project.service()
    }
}
