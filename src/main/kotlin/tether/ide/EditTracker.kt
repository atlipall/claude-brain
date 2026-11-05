package tether.ide

import tether.git.canonicalPath
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Records files the user saved from the IDE, by absolute path. Claude's own writes are disk changes that the
 * IDE reloads (never saves), so they are not recorded. Each chat (a key) has its own cursor: it is told about
 * a file once, and only when the file is in its folder.
 */
@Service(Service.Level.PROJECT)
class EditTracker(private val project: Project) : Disposable {
    private val lock = Any()
    /** Absolute path -> sequence number of its latest save. */
    private val saved = LinkedHashMap<String, Long>()
    private var seq = 0L
    /** True while [saveForClaude] saves: those saves are not the user's edits. EDT only. */
    private var quiet = false
    /** Chat key -> sequence number it has been told up to. */
    private val cursors = HashMap<Any, Long>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    if (quiet) return
                    FileDocumentManager.getInstance().getFile(document)?.let(::record)
                }
            })
    }

    /**
     * Runs [change] (a quick fix or refactoring Claude asked for) and saves the documents it modified, returning their
     * files. Documents that had no unsaved edits are saved without recording them, so no chat hears of Claude's own
     * change as the user's edit. One the user was already editing is saved as usual: it carries their unsaved text
     * too, which chats should hear about. EDT.
     */
    fun saveChangedBy(change: () -> Unit): List<VirtualFile> {
        val files = FileDocumentManager.getInstance()
        val before = files.unsavedDocuments.associateWith { it.modificationStamp }
        change()
        val touched = files.unsavedDocuments.filter { before[it] != it.modificationStamp }
        val (users, claudes) = touched.partition { it in before }
        quiet = true
        try {
            claudes.forEach(files::saveDocument)
        } finally {
            quiet = false
        }
        users.forEach(files::saveDocument)
        return touched.mapNotNull(files::getFile)
    }

    fun record(file: VirtualFile) {
        if (project.isDisposed) return
        synchronized(lock) {
            saved.remove(file.path)
            saved[file.path] = ++seq
        }
    }

    /** From now on [key] hears about saves made after this call only. */
    fun register(key: Any) {
        synchronized(lock) { cursors[key] = seq }
    }

    fun forget(key: Any) {
        synchronized(lock) { cursors.remove(key) }
    }

    /**
     * Saves all documents (recording unsaved user edits), then returns the note for [key]'s next message:
     * files saved since its last call that lie under [cwd] (not under the worktrees folder inside it), or null.
     * Advances only [key]. EDT only.
     */
    fun consumeNote(key: Any, cwd: String): String? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val files = synchronized(lock) {
            val since = cursors[key] ?: 0L
            cursors[key] = seq
            saved.filterValues { it > since }.keys.toList()
        }
        return EditNotes.note(EditNotes.underFolder(cwd, files))
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): EditTracker = project.service()
    }
}

/** Which saved files a chat is told about, and the note text. */
object EditNotes {
    /**
     * [paths] under [cwd] (excluding worktrees claude keeps in `cwd/.claude/worktrees`), relative to [cwd].
     * Both sides are compared by canonical path, so symlinks and trailing slashes do not matter.
     */
    fun underFolder(cwd: String, paths: List<String>): List<String> {
        val dir = canonicalPath(cwd).trimEnd('/') + "/"
        val worktrees = dir + ".claude/worktrees/"
        return paths.map(::canonicalPath).filter { it.startsWith(dir) && !it.startsWith(worktrees) }.map { it.removePrefix(dir) }
    }

    fun note(files: List<String>): String? =
        if (files.isEmpty()) null
        else "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\n" +
            "Re-read them before relying on earlier contents."
}
