package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch

/** Receives one chat's updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)

    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}
}

/** One chat tab's model: its own [ChatSession] and claude process, the items shown so far, and the page showing them. */
class Chat(
    val id: Int,
    spec: ChatSpec,
    history: List<ChatItem>,
    connector: (SessionLaunch) -> CliConnection,
    initialMode: PermissionMode?,
    private val editNote: (Chat) -> String?,
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

    private val listener = object : ChatListener {
        override fun onItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
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

    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
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

    /** EDT only (the edit note saves documents). Ignored while the session is dead. */
    fun send(text: String) {
        if (session.state == SessionState.DEAD) return
        if (firstMessage == null) {
            firstMessage = text
            onTitleChanged?.invoke()
        }
        session.send(text, editNote(this))
    }

    fun dispose() = session.dispose()
}
