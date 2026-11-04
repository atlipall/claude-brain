package claudechat.ide

import claudechat.context.ContextNotes
import claudechat.follow.CheckoutMapper
import claudechat.session.Chat
import claudechat.session.ChatItem
import claudechat.session.Checkpoints
import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.history.LocalHistoryException
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/** Most checkpoints kept per chat; older messages can't be reverted to. */
private const val MAX_PER_CHAT = 50

/**
 * Local History labels put as each message goes out to Claude, so a chat's checkout can be reverted to how it was
 * before any message of this run of the IDE (resumed history has none). Each revert is itself undoable from Local
 * History. Checkouts Local History does not track (excluded folders, such as worktrees by default) get no labels.
 */
@Service(Service.Level.PROJECT)
class TurnCheckpoints(private val project: Project) : Checkpoints {
    private class Checkpoint(val label: Label, val dir: String, val message: String)

    /** Chat id -> its newest checkpoints by message item id, oldest first. */
    private val checkpoints = ConcurrentHashMap<Int, MutableMap<String, Checkpoint>>()
    /** Chat id -> what to tell Claude with the next message after a revert. */
    private val notes = ConcurrentHashMap<Int, String>()

    override fun mark(chat: Chat, item: ChatItem.UserText): Boolean = try {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(chat.cwd)
        if (dir == null || !tracked(dir)) {
            false
        } else {
            // Writes the previous turn made behind the IDE's back belong before this label, not after it.
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val message = shorten(item.text)
            val label = LocalHistory.getInstance().putSystemLabel(project, "Tether: before “$message”")
            val forChat = checkpoints.computeIfAbsent(chat.id) {
                java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Checkpoint>() {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Checkpoint>) = size > MAX_PER_CHAT
                })
            }
            forChat[item.id] = Checkpoint(label, dir.path, message)
            true
        }
    } catch (e: Exception) {
        LOG.warn("Could not put a Local History label", e)
        false
    }

    override fun revert(chat: Chat, itemId: String) {
        val checkpoint = checkpoints[chat.id]?.get(itemId)
        if (checkpoint == null) {
            Messages.showInfoMessage(project, "This message is too old to revert to.", "Revert to Before This Message")
            return
        }
        // A turn's edits in flight would land after the revert; the user stops it (and sees what it did) first.
        if (chat.busy) {
            Messages.showInfoMessage(project, "${chat.agent.name} is still working. Stop it, or wait for it to finish, then revert.", "Revert to Before This Message")
            return
        }
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(checkpoint.dir) ?: return
        val answer = Messages.showYesNoDialog(
            project,
            "Revert every file in ${dir.presentableUrl} to how it was before “${checkpoint.message}”?\n\n" +
                "This undoes ${chat.agent.name}'s changes from that message on, and your own changes since then too. " +
                "You can undo the revert from Local History.",
            "Revert to Before This Message", "Revert", "Cancel", Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        // Local History must have the latest state, Claude's last writes included, so the revert is complete and undoable.
        FileDocumentManager.getInstance().saveAllDocuments()
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        try {
            targets(dir).forEach { checkpoint.label.revert(project, it) }
        } catch (e: LocalHistoryException) {
            LOG.warn("Revert failed", e)
            chat.session.notice("Couldn't revert: ${e.message}")
            return
        }
        chat.session.notice("Reverted the files to how they were before “${checkpoint.message}”.", isError = false)
        notes[chat.id] = "The user reverted the files in this checkout to how they were before their message " +
            "“${checkpoint.message}”. Changes made since then are gone; re-read files before relying on what you remember of them."
    }

    /** Whether Local History keeps [dir]'s files: inside the project's content and not excluded. */
    private fun tracked(dir: VirtualFile): Boolean =
        ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(dir) }

    /**
     * What to revert under [dir]: all of it, except that a project folder holding worktrees is reverted entry by entry
     * without `.claude`, so a main chat never reverts a worktree's files. (A top-level entry deleted since the label is
     * then not restored.)
     */
    private fun targets(dir: VirtualFile): List<VirtualFile> {
        if (dir.findFileByRelativePath(CheckoutMapper.WORKTREES) == null) return listOf(dir)
        return dir.children.filter { it.name != ".claude" }
    }

    /** The note for chat [chatId]'s next message after a revert, once. */
    fun consumeNote(chatId: Int): String? = notes.remove(chatId)

    fun forget(chatId: Int) {
        checkpoints.remove(chatId)
        notes.remove(chatId)
    }

    private fun shorten(text: String) = ContextNotes.clean(text, 60).ifEmpty { "(image)" }

    companion object {
        private val LOG = logger<TurnCheckpoints>()
        fun getInstance(project: Project): TurnCheckpoints = project.service()
    }
}
