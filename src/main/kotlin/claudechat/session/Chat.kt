package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.ContextUsage
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.context.ContextNotes
import claudechat.context.EditorContext
import claudechat.image.Attachment
import claudechat.image.PendingAttachments
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.atomic.AtomicBoolean

/** Receives one chat's updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)

    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}

    /** What Claude is doing now (changes only); null when no turn is running. */
    fun activity(activity: Activity?) {}

    /** How full the context window is (the latest report). */
    fun context(usage: ContextUsage) {}

    /** A predicted next prompt for the empty message box, valid until the next state other than IDLE. */
    fun suggestion(text: String) {}

    /** The messages waiting for the running turn to end, oldest first; empty hides them. */
    fun queue(messages: List<QueuedMessage>) {}
}

/** Marks the files' state before each message of a chat, and reverts to such a mark. */
interface Checkpoints {
    /** Marks the files before [item] goes out; true when it can be reverted to. Pooled thread, outside every lock. */
    fun mark(chat: Chat, item: ChatItem.UserText): Boolean

    /** Reverts [chat]'s files to how they were before message [itemId] (asking first). EDT. */
    fun revert(chat: Chat, itemId: String)
}

/** One chat tab's model: its own [ChatSession] and claude process, the items shown so far, and the page showing them. */
class Chat(
    val id: Int,
    spec: ChatSpec,
    history: List<ChatItem>,
    connector: (SessionLaunch) -> CliConnection,
    initialMode: PermissionMode?,
    private val editNote: (Chat) -> String?,
    /**
     * Where the user is looking, as this chat names the file; null for none (or when the setting is off). EDT, cheap:
     * it must not search for symbols.
     */
    private val editorContext: (Chat) -> EditorContext? = { null },
    /**
     * The same with the symbol, which may need a background lookup: calls `done` on the EDT, at once or later, exactly
     * once. Null means [editorContext] is already complete.
     */
    private val resolveContext: ((Chat, (EditorContext?) -> Unit) -> Unit)? = null,
    log: (String) -> Unit = {},
    /** When the session began (epoch millis): now for a new chat, the transcript's start for a resumed one. */
    val startedAt: Long? = null,
    /** Tools and edit checks the IDE lends this chat's Claude; null for none. */
    ide: IdeHost? = null,
    /** Runs IDE work off the CLI reader thread and outside the session lock. */
    private val background: (Runnable) -> Unit = { it.run() },
    /** Marks the files' state as each message goes out, so they can be reverted to it; null for none. */
    private val checkpoints: Checkpoints? = null,
    /** The name shown until the user gives one (`main`, `main 2`, a worktree name). */
    private val defaultName: String = spec.label,
    /** Stores a name the user gave in the session's transcript (blank: none), marked archived or not. Called off the EDT. */
    private val saveTitle: ((cwd: String, sessionId: String, title: String, archived: Boolean) -> Unit)? = null,
) {
    val label: String = spec.label

    /** The commit HEAD was at when this chat started, recorded off the EDT shortly after; null until then or unknown. */
    @Volatile var startCommit: String? = null

    private val lock = Any()
    private val items = LinkedHashMap<String, ChatItem>().apply { history.forEach { put(it.id, it) } }
    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null

    /** The name the user gave this chat (or its resumed session); null for the default name. */
    @Volatile var customTitle: String? = ChatNames.clean(spec.title)
        private set
    /** True while [customTitle] waits to be saved, as when it changed before the session had an id. */
    private val titleUnsaved = AtomicBoolean(false)
    /** Held only by the background writes, so they write in order and never block the CLI reader thread. */
    private val titleWriteLock = Any()
    @Volatile private var archived = false

    /** Called on the EDT when [title] changes. */
    @Volatile var onTitleChanged: (() -> Unit)? = null

    /** Called on the CLI reader thread, with the session lock held, when [cwd] changes (system/init reported another folder). */
    @Volatile var onCwdChanged: (() -> Unit)? = null
    @Volatile private var knownCwd: String? = null

    /** The header text the page shows (see [SessionView.checkout]). */
    @Volatile var checkoutText: String = ""
        private set

    /** True while a turn is running or waiting for the user, or background agents are working. */
    val busy: Boolean get() = synchronized(activityLock) { sessionActivity != null }

    /** Called on the CLI reader thread (or the EDT) when [busy] changes. */
    @Volatile var onBusyChanged: (() -> Unit)? = null

    @Volatile private var contextUsage: ContextUsage? = null
    /** The latest prompt suggestion, kept while the session stays idle (replayed on attach). */
    @Volatile private var suggestion: String? = null
    /** The session's queued messages (replayed on attach). */
    @Volatile private var queued: List<QueuedMessage> = emptyList()

    private val activityLock = Any()
    private var sessionActivity: Activity? = null
    private var shownActivity: Activity? = null
    /** True while sent messages wait for their editor context (written on the EDT, read anywhere). */
    @Volatile private var sendPending = false

    /** Tells the view the activity if it changed: the session's, or Starting while a send waits for its context. */
    private fun publishActivity() {
        synchronized(activityLock) {
            val session = sessionActivity
            val now = if (sendPending && (session == null || session is Activity.Agents)) Activity.Starting else session
            if (now == shownActivity) return
            shownActivity = now
            view?.activity(now)
        }
    }

    private val listener = object : ChatListener {
        override fun onActivity(activity: Activity?) {
            val wasBusy = synchronized(activityLock) { (sessionActivity != null).also { sessionActivity = activity } }
            publishActivity()
            if (wasBusy != (activity != null)) onBusyChanged?.invoke()
        }

        override fun onContext(usage: ContextUsage) {
            if (usage == contextUsage) return
            contextUsage = usage
            view?.context(usage)
        }

        override fun onSuggestion(text: String) {
            suggestion = text
            view?.suggestion(text)
        }

        override fun onQueue(messages: List<QueuedMessage>) {
            queued = messages
            view?.queue(messages)
        }

        /** Their text goes back into the message box, so a stopped turn loses nothing the user typed (images aside). */
        override fun onDropped(messages: List<QueuedMessage>) {
            val text = messages.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
            if (text.isNotEmpty()) insert(text)
        }

        override fun onItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
            // Every UserText the session reports is a message going out now (history comes through the constructor).
            // The mark is IDE work (Local History): off this thread, which holds the session lock.
            if (item is ChatItem.UserText) checkpoints?.let { c -> background { if (c.mark(this@Chat, item)) showRevertable(item.id) } }
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            if (state != SessionState.IDLE) suggestion = null
            saveTitleIfReady()
            view?.state(state, sessionId, mode)
            val now = cwd
            if (now != knownCwd) {
                knownCwd = now
                onCwdChanged?.invoke()
            }
        }
    }

    val session = ChatSession(connector, listener, spec.cwd, spec.resumeId, initialMode, spec.worktree, log, ide, background)

    private val predictedCwd: String? = spec.worktree?.let { "${spec.cwd.trimEnd('/')}/.claude/worktrees/$it" }

    /**
     * Absolute folder claude works in. For a new worktree this is the predicted `.claude/worktrees/<name>` until
     * system/init (which arrives only after the first message) reports the real one.
     */
    val cwd: String get() = if (session.cwdReported) session.cwd else predictedCwd ?: session.cwd

    val title: String get() = customTitle ?: defaultName

    /** Renames the chat; a blank [name] restores the default. The name is saved with the session, now or once it has an id. EDT. */
    fun rename(name: String) {
        val clean = ChatNames.clean(name)
        if (clean == customTitle) return
        customTitle = clean
        onTitleChanged?.invoke()
        titleUnsaved.set(true)
        saveTitleIfReady()
    }

    /**
     * Marks the session archived (hidden from the resume list until resumed from the archived list); the caller closes
     * the chat. False when there is nothing to archive: no session yet, as before the first message. EDT.
     */
    fun archive(): Boolean {
        if (session.sessionId == null) return false
        archived = true
        titleUnsaved.set(true)
        saveTitleIfReady()
        return true
    }

    /** Saves an unsaved name once the session has an id (a new chat gets one with its first message). Any thread. */
    private fun saveTitleIfReady() {
        val save = saveTitle ?: return
        val id = session.sessionId ?: return
        if (!titleUnsaved.getAndSet(false)) return
        val folder = cwd
        // Reads the name when it runs, under the lock, so the last write is always the latest name.
        background { synchronized(titleWriteLock) { save(folder, id, customTitle.orEmpty(), archived) } }
    }

    fun start() {
        knownCwd = cwd
        session.start()
    }

    /** EDT only. */
    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        synchronized(activityLock) { shownActivity }?.let(view::activity)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
        contextUsage?.let(view::context)
        suggestion?.let(view::suggestion)
        queued.takeIf { it.isNotEmpty() }?.let(view::queue)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else synchronized(lock) { pendingInsert = text }
    }

    fun showCheckout(text: String) {
        if (text == checkoutText) return
        checkoutText = text
        view?.checkout(text)
    }

    /** The formatted editor context, or null when there is none or capturing it failed (a failure must never block sending). */
    private fun captureContext(format: (EditorContext) -> String): String? = try {
        editorContext(this)?.let(format)
    } catch (e: Exception) {
        if (e is ProcessCanceledException || e is ControlFlowException) throw e
        logger<Chat>().warn("Editor context capture failed; going on without it", e)
        null
    }

    /** Images waiting above this chat's message box; [send] takes them out. EDT only. */
    val attachments = PendingAttachments()

    private class Outgoing(val text: String, val edits: String?, val images: List<Attachment>)

    /** Messages waiting for their context, oldest first; only the head resolves, so order is kept. EDT only. */
    private val outbox = ArrayDeque<Outgoing>()
    private var resolving = false
    private var disposed = false

    /**
     * EDT only (the edit note saves documents, the context reads the editor). Ignored while the session is dead.
     * Claude gets the edit note, then the editor-context note, then [text], then the [images]; the chat shows [text]
     * under the images' thumbnails. Image bytes are dropped once the line is written to the CLI.
     * The edit note is taken now. When the symbol at the caret is not cached the message goes
     * out (and its bubble appears) once the background lookup returns, behind any earlier message still waiting.
     */
    fun send(text: String, images: List<Attachment> = emptyList()) {
        if (session.state == SessionState.DEAD) return
        if (text.isBlank() && images.isEmpty()) return
        val edits = editNote(this) // saves documents first
        outbox += Outgoing(text, edits, images)
        pump()
    }

    private fun pump() {
        try {
            drain()
        } finally {
            sendPending = outbox.isNotEmpty()
            publishActivity()
        }
    }

    private fun drain() {
        while (!resolving) {
            val next = outbox.firstOrNull() ?: return
            val resolve = resolveContext
            if (resolve == null) {
                outbox.removeFirst()
                deliver(next, captureContext(ContextNotes::note))
                continue
            }
            resolving = true
            var inline = true
            var ready = false
            var note: String? = null
            val done = { context: EditorContext? ->
                val formatted = format(context)
                if (inline) {
                    note = formatted
                    ready = true
                } else if (!disposed && outbox.firstOrNull() === next) { // a closed chat's outbox was cleared
                    resolving = false
                    outbox.removeFirst()
                    deliver(next, formatted)
                    pump()
                }
            }
            try {
                resolve(this, done)
            } catch (e: Exception) {
                if (e is ProcessCanceledException || e is ControlFlowException) throw e
                logger<Chat>().warn("Editor context capture failed; going on without it", e)
                if (!ready) { ready = true; note = null }
            }
            inline = false
            if (ready) {
                resolving = false
                outbox.removeFirst()
                deliver(next, note)
            }
        }
    }

    private fun format(context: EditorContext?): String? = try {
        context?.let(ContextNotes::note)
    } catch (e: Exception) {
        if (e is ProcessCanceledException || e is ControlFlowException) throw e
        null
    }

    private fun deliver(message: Outgoing, context: String?) {
        if (disposed || session.state == SessionState.DEAD) return
        session.send(
            message.text,
            listOfNotNull(message.edits, context).joinToString("\n\n").ifEmpty { null },
            message.images.map { it.image },
            message.images.map { it.thumb },
        )
    }

    /** Puts queued message [id] back into the message box instead of sending it. */
    fun editQueued(id: String) {
        session.unqueue(id)?.text?.takeIf { it.isNotBlank() }?.let(::insert)
    }

    /** Reverts the files to how they were before message [itemId], if it is [ChatItem.UserText.revertable]. EDT. */
    fun revertTo(itemId: String) {
        val item = synchronized(lock) { items[itemId] } as? ChatItem.UserText ?: return
        if (item.revertable) checkpoints?.revert(this, itemId)
    }

    private fun showRevertable(itemId: String) {
        val shown = synchronized(lock) {
            (items[itemId] as? ChatItem.UserText)?.copy(revertable = true)?.also { items[itemId] = it }
        } ?: return
        view?.item(shown)
    }

    /** Stops the turn, and drops messages still waiting for their editor context. EDT only. */
    fun interrupt() {
        if (outbox.isNotEmpty()) {
            outbox.clear()
            resolving = false
            sendPending = false
        }
        session.interrupt()
        publishActivity()
    }

    fun dispose() {
        disposed = true
        outbox.clear()
        sendPending = false
        attachments.clear()
        session.dispose()
    }
}
