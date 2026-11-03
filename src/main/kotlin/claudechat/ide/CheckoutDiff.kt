package claudechat.ide

import claudechat.git.CheckoutChanges
import claudechat.git.DiffBase
import claudechat.git.DiffBases
import claudechat.git.FileChange
import claudechat.session.Chat
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.vcsUtil.VcsUtil
import java.io.File

/**
 * Shows everything that changed in a chat's checkout, in the IDE's changes browser: since main on a branch, otherwise
 * since the session started (see [DiffBases.resolve]). Git runs in the background; the browser opens on the EDT.
 */
object CheckoutDiff {
    /** EDT. */
    fun show(project: Project, chat: Chat) {
        FileDocumentManager.getInstance().saveAllDocuments() // git reads the disk
        val cwd = chat.cwd
        object : Task.Backgroundable(project, "Collecting changes", true) {
            private var base: DiffBase? = null
            private var changes: List<Change>? = null

            override fun run(indicator: ProgressIndicator) {
                val found = DiffBases().resolve(cwd, chat.startCommit, chat.startedAt) ?: return
                base = found
                val checkout = CheckoutChanges()
                val files = checkout.list(found) ?: return
                indicator.checkCanceled()
                LocalFileSystem.getInstance().refreshIoFiles(
                    files.filter { it.kind != FileChange.Kind.DELETED }.map { File(found.root, it.path) }, false, false, null)
                changes = toChanges(found, files, checkout)
            }

            override fun onSuccess() {
                val base = base
                val changes = changes
                when {
                    base == null -> chat.session.notice("Can't show changes: $cwd is not in a git repository with commits.")
                    changes == null -> chat.session.notice("Can't show changes: git failed in ${base.root}.")
                    changes.isEmpty() -> chat.session.notice("No changes since ${base.label}.", isError = false)
                    else -> AbstractVcsHelper.getInstance(project).showWhatDiffersBrowser(changes, base.title)
                }
            }
        }.queue()
    }

    /** IDE changes for [files]: the base side reads `git show <commit>:<path>` when first asked, the other side the disk. */
    internal fun toChanges(base: DiffBase, files: List<FileChange>, changes: CheckoutChanges): List<Change> =
        files.map { toChange(base, it, changes) }

    private fun toChange(base: DiffBase, file: FileChange, changes: CheckoutChanges): Change {
        fun path(relative: String): FilePath = VcsUtil.getFilePath(File(base.root, relative).path, false)
        fun before(): ContentRevision {
            val relative = file.oldPath ?: file.path
            val filePath = path(relative)
            val number = BaseRevisionNumber(base.commit)
            val load = lazy { changes.baseContent(base, relative) }
            return if (filePath.fileType.isBinary) BinaryBaseRevision(filePath, number, load) else BaseRevision(filePath, number, load)
        }
        fun after() = CurrentContentRevision.create(path(file.path))
        return when (file.kind) {
            FileChange.Kind.ADDED -> Change(null, after())
            FileChange.Kind.DELETED -> Change(before(), null)
            FileChange.Kind.MODIFIED, FileChange.Kind.RENAMED -> Change(before(), after())
        }
    }

    /** A file at the base commit; [load] runs `git show` the first time the diff asks for it. */
    private open class BaseRevision(
        private val file: FilePath,
        private val number: VcsRevisionNumber,
        protected val load: Lazy<ByteArray?>,
    ) : ByteBackedContentRevision {
        override fun getContentAsBytes(): ByteArray? = load.value
        override fun getContent(): String? = load.value?.toString(file.charset)
        override fun getFile() = file
        override fun getRevisionNumber() = number
    }

    /** The diff viewer recognises binary files by this interface. */
    private class BinaryBaseRevision(file: FilePath, number: VcsRevisionNumber, load: Lazy<ByteArray?>) :
        BaseRevision(file, number, load), BinaryContentRevision {
        override fun getBinaryContent(): ByteArray? = load.value
        override fun getContentAsBytes(): ByteArray? = load.value
        override fun getContent(): String? = null
    }

    private class BaseRevisionNumber(private val commit: String) : VcsRevisionNumber {
        override fun asString(): String = commit.take(7)
        override fun compareTo(other: VcsRevisionNumber?): Int = 0
    }
}
