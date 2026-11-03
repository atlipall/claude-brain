package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.context.ContextNotes
import claudechat.context.EditorContext
import claudechat.image.Attachment
import claudechat.image.PendingAttachments
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException

/** Receives one chat's updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)

    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}

    /** The editor-context chip: empty [text] hides it; [off] shows it muted (left out of the next message). */
    fun context(text: String, off: Boolean) {}

    /** What Claude is doing now (changes only); null when no turn is running. */
    fun activity(activity: Activity?) {}
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
     * the chip reads it on every refresh, so it must not search for symbols.
     */
    private val editorContext: (Chat) -> EditorContext? = { null },
    /**
     * The same with the symbol, which may need a background lookup: calls `done` on the EDT, at once or later, exactly
     * once. Null means [editorContext] is already complete.
     */
    private val resolveContext: ((Chat, (EditorContext?) -> Unit) -> Unit)? = null,
    log: (String) -> Unit = {},
) {
    val label: String = spec.label

    private val lock = Any()
    private val items = LinkedHashMap<String, ChatItem>().apply { history.forEach { put(it.id, it) } }
    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null

    /** The first message the user sent, or the first one of a resumed transcript. */
    @Volatile var firstMessage: String? = history.filterIsInstance<ChatItem.UserText>().firstOrNull()?.text
        private set

    /** Called on the EDT when [title] changes. */
    @Volatile var onTitleChanged: (() -> Unit)? = null

    /** Called on the CLI reader thread, with the session lock held, when [cwd] changes (system/init reported another folder). */
    @Volatile var onCwdChanged: (() -> Unit)? = null
    @Volatile private var knownCwd: String? = null

    /** The header text the page shows (see [SessionView.checkout]). */
    @Volatile var checkoutText: String = ""
        private set

    /** True while a turn is running or waiting for the user. */
    @Volatile var busy = false
        private set

    /** Called on the CLI reader thread (or the EDT) when [busy] changes. */
    @Volatile var onBusyChanged: (() -> Unit)? = null

    private val activityLock = Any()
    private var sessionActivity: Activity? = null
    private var shownActivity: Activity? = null
    /** True while sent messages wait for their editor context (written on the EDT, read anywhere). */
    @Volatile private var sendPending = false

    /** Tells the view the activity if it changed: the session's, or Starting while a send waits for its context. */
    private fun publishActivity() {
        synchronized(activityLock) {
            val now = sessionActivity ?: if (sendPending) Activity.Starting else null
            if (now == shownActivity) return
            shownActivity = now
            view?.activity(now)
        }
    }

    private val listener = object : ChatListener {
        override fun onActivity(activity: Activity?) {
            synchronized(activityLock) { sessionActivity = activity }
            publishActivity()
        }

        override fun onItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            val nowBusy = state == SessionState.RUNNING || state == SessionState.AWAITING_PERMISSION
            if (nowBusy != busy) {
                busy = nowBusy
                onBusyChanged?.invoke()
            }
            view?.state(state, sessionId, mode)
            val now = cwd
            if (now != knownCwd) {
                knownCwd = now
                onCwdChanged?.invoke()
            }
        }
    }

    val session = ChatSession(connector, listener, spec.cwd, spec.resumeId, initialMode, spec.worktree, log)

    private val predictedCwd: String? = spec.worktree?.let { "${spec.cwd.trimEnd('/')}/.claude/worktrees/$it" }

    /**
     * Absolute folder claude works in. For a new worktree this is the predicted `.claude/worktrees/<name>` until
     * system/init (which arrives only after the first message) reports the real one.
     */
    val cwd: String get() = if (session.cwdReported) session.cwd else predictedCwd ?: session.cwd

    val title: String get() = ChatNames.tabTitle(label, firstMessage)

    fun start() {
        knownCwd = cwd
        session.start()
    }

    /** EDT only (the chip reads the editor). */
    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        synchronized(activityLock) { shownActivity }?.let(view::activity)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
        shownContext = HIDDEN // a new page shows no chip yet
        refreshContext()
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

    /** True after the user turned editor context off for the next message only. EDT only. */
    var contextOff = false
        private set

    /** Leaves the editor context out of the next message ([off]) or puts it back. EDT only. */
    fun skipContext(off: Boolean) {
        contextOff = off
        refreshContext()
    }

    /** The formatted editor context, or null when there is none or capturing it failed (a failure must never block sending). */
    private fun captureContext(format: (EditorContext) -> String): String? = try {
        editorContext(this)?.let(format)
    } catch (e: Exception) {
        if (e is ProcessCanceledException || e is ControlFlowException) throw e
        logger<Chat>().warn("Editor context capture failed; going on without it", e)
        null
    }

    /** The chip text and muted state the page shows. EDT only. */
    private var shownContext = HIDDEN

    /** Recomputes the chip and tells the page when its text or muted state changed. EDT only. */
    fun refreshContext() {
        if (view == null) return // a detached chat has no chip; attach resets it and refreshes
        val next = captureContext(ContextNotes::chip).orEmpty() to contextOff
        if (next == shownContext) return
        shownContext = next
        view?.context(next.first, next.second)
    }

    /** Images waiting above this chat's message box; [send] takes them out. EDT only. */
    val attachments = PendingAttachments()

    private class Outgoing(val text: String, val edits: String?, val withContext: Boolean, val images: List<Attachment>)

    /** Messages waiting for their context, oldest first; only the head resolves, so order is kept. EDT only. */
    private val outbox = ArrayDeque<Outgoing>()
    private var resolving = false
    private var disposed = false

    /**
     * EDT only (the edit note saves documents, the context reads the editor). Ignored while the session is dead.
     * Claude gets the edit note, then the editor-context note, then [text], then the [images]; the chat shows [text]
     * under the images' thumbnails. Image bytes are dropped once the line is written to the CLI.
     * The edit note and the skip-once flag are taken now. When the symbol at the caret is not cached the message goes
     * out (and its bubble appears) once the background lookup returns, behind any earlier message still waiting.
     */
    fun send(text: String, images: List<Attachment> = emptyList()) {
        if (session.state == SessionState.DEAD) return
        if (text.isBlank() && images.isEmpty()) return
        if (firstMessage == null) {
            firstMessage = text.ifBlank { IMAGE_TITLE }
            onTitleChanged?.invoke()
        }
        val edits = editNote(this) // saves documents first
        outbox += Outgoing(text, edits, !contextOff, images)
        contextOff = false
        pump()
        refreshContext() // the chip is live again
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
            if (!next.withContext || resolve == null) {
                outbox.removeFirst()
                deliver(next, if (next.withContext) captureContext(ContextNotes::note) else null)
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

    private companion object {
        val HIDDEN = "" to false
        const val IMAGE_TITLE = "[image]"
    }
}
