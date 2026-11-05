package tether.ide

import tether.git.RepoState
import tether.git.RepoStates
import tether.git.insideRepository
import tether.session.Chat
import tether.session.GitNotes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tells each chat what happened in git that it did not do: commits, pushes, pulls, branch switches, resets, stashes
 * and merges or rebases started or finished, whether from the IDE, a terminal or another tool.
 *
 * Each chat has a known state: the checkout's state when its last turn ended (or when it opened). Its next message
 * compares the checkout now with that and carries a note of the difference. Everything done with git during a turn,
 * Claude's own commands included, is part of the known state once the turn ends, so it is never reported back.
 *
 * All git runs on one background queue, in order, so a message never reads the state before the previous turn's end
 * was recorded.
 */
@Service(Service.Level.PROJECT)
class GitTracker : GitNotes {
    private class Known(val dir: String, val state: RepoState)

    /** Chat id -> the git state it knows. Read and written on [queue] only. */
    private val known = HashMap<Int, Known>()
    private val queue = AppExecutorUtil.createBoundedApplicationPoolExecutor("Tether git state", 1)

    var states = RepoStates()
        @TestOnly set

    /** How long a message waits for its note before it goes out without one. */
    var timeoutMs = 3_000L
        @TestOnly set

    /** Records [dir]'s current state as what chat [chatId] knows, in the background. Any thread. */
    fun settle(chatId: Int, dir: String) {
        if (!insideRepository(dir)) return forget(chatId)
        queue.execute { remember(chatId, dir, read(dir)) }
    }

    fun forget(chatId: Int) {
        queue.execute { known.remove(chatId) }
    }

    override fun turnEnded(chat: Chat) = settle(chat.id, chat.cwd)

    /**
     * Calls [done] on the EDT, exactly once, with the note for [chat]'s next message: what changed in its folder's git
     * state since its known state, or null. Outside a repository [done] runs at once. The known state stays as it is:
     * the turn the message starts records the new one when it ends ([turnEnded]), and a message that never goes out
     * leaves the change to be reported with the next. A note that takes longer than [timeoutMs] is dropped (null) and
     * the work behind it skipped, so what it would have said is lost once that turn ends.
     */
    override fun note(chat: Chat, done: (String?) -> Unit) {
        val chatId = chat.id
        val dir = chat.cwd
        if (!insideRepository(dir)) return done(null)
        val finished = AtomicBoolean()
        val timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (finished.compareAndSet(false, true)) onEdt { done(null) }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        queue.execute {
            if (finished.get()) return@execute
            val now = read(dir)
            if (finished.get()) return@execute
            val note = describe(known[chatId], dir, now)
            if (finished.compareAndSet(false, true)) {
                timeout.cancel(false)
                onEdt { done(note) }
            }
        }
    }

    /** Waits for every queued read and note. */
    @TestOnly
    fun drain() {
        queue.submit {}.get(10, TimeUnit.SECONDS)
    }

    /** Null without a known state, or when the chat moved to another folder (its git state is another's). */
    private fun describe(before: Known?, dir: String, now: RepoState?): String? {
        if (before == null || now == null || before.dir != dir || before.state == now) return null
        return try {
            states.describe(dir, before.state, now)
        } catch (e: Exception) {
            LOG.warn("Couldn't describe the git changes in $dir", e)
            null
        }
    }

    private fun read(dir: String): RepoState? = try {
        states.read(dir)
    } catch (e: Exception) {
        LOG.warn("Couldn't read the git state of $dir", e)
        null
    }

    private fun remember(chatId: Int, dir: String, state: RepoState?) {
        if (state == null) known.remove(chatId) else known[chatId] = Known(dir, state)
    }

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.defaultModalityState())

    companion object {
        private val LOG = logger<GitTracker>()
        fun getInstance(project: Project): GitTracker = project.service()
    }
}
