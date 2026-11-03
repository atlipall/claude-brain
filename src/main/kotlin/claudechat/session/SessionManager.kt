package claudechat.session

import claudechat.cli.ClaudeBinary
import claudechat.cli.ClaudeCommand
import claudechat.cli.ClaudeProcess
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.ide.EditTracker
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File


@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    var store: SessionStore = SessionStore.default()
    var current: ChatSession? = null
        private set

    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null
    private val items = LinkedHashMap<String, ChatItem>()

    private val listener = object : ChatListener {
        override fun onItem(item: ChatItem) {
            synchronized(items) { items[item.id] = item }
            view?.item(item)
        }
        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
        }
    }

    init {
        EditTracker.getInstance(project) // start recording saves before the first message
    }

    fun attach(view: SessionView) {
        this.view = view
        val session = current
        if (session == null) {
            newChat()
        } else {
            view.reset(synchronized(items) { items.values.toList() })
            view.state(session.state, session.sessionId, session.mode)
        }
        pendingInsert?.let { view.insert(it); pendingInsert = null }
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun newChat() = open(resumeId = null, history = emptyList())

    fun resume(sessionId: String) = open(sessionId, store.transcript(cwd(), sessionId))

    /** EDT only (saves documents to compute the edit note). */
    fun send(text: String) {
        val session = current ?: return
        session.send(text, EditTracker.getInstance(project).consumeNote())
    }

    fun listSessions(): List<SessionSummary> = store.list(cwd())

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else pendingInsert = text
    }

    private fun open(resumeId: String?, history: List<ChatItem>) {
        current?.dispose()
        EditTracker.getInstance(project).clear()
        synchronized(items) {
            items.clear()
            history.forEach { items[it.id] = it }
        }
        view?.reset(history)
        val session = ChatSession(connector, listener, cwd(), resumeId, defaultMode(), log = { LOG.info(it) })
        current = session
        session.start()
    }

    private fun defaultMode(): PermissionMode? = PermissionMode.fromCli(ClaudeChatSettings.getInstance().state.defaultMode)

    private fun cwd(): String = project.basePath ?: System.getProperty("user.home")

    private fun defaultConnect(launch: SessionLaunch): CliConnection {
        val settings = ClaudeChatSettings.getInstance().state
        val env = EnvironmentUtil.getEnvironmentMap()
        val binary = ClaudeBinary.resolve(settings.claudePath.orEmpty(), env["PATH"], System.getProperty("user.home")) {
            File(it).canExecute()
        } ?: throw ClaudeNotFoundException()
        val command = ClaudeCommand.build(binary, launch, ParametersListUtil.parse(settings.extraArgs.orEmpty()))
        return ClaudeProcess(command, File(launch.cwd), env)
    }

    override fun dispose() {
        current?.dispose()
        current = null
    }

    companion object {
        private val LOG = logger<SessionManager>()
        fun getInstance(project: Project): SessionManager = project.service()
    }
}
