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

    private val listener = object : ChatListener {
        override fun onItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
        }
    }

    val session = ChatSession(connector, listener, spec.cwd, spec.resumeId, initialMode, spec.worktree, log)

    /** Absolute folder claude runs in (the worktree path once system/init reported it). */
    val cwd: String get() = session.cwd

    val title: String get() = ChatNames.tabTitle(label, firstMessage)

    fun start() = session.start()

    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else synchronized(lock) { pendingInsert = text }
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
