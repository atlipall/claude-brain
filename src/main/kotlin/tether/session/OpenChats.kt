package tether.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

/**
 * The sessions whose chats were open when the project closed, so the next start can resume them. The list follows the
 * open chats only after [startTracking], once the restored tabs are open: until then (the Tether window not opened yet,
 * or still restoring) it is kept as loaded. From the project's closing on it is frozen, so chats closed by the shutdown
 * stay in it.
 */
@Service(Service.Level.PROJECT)
@State(name = "TetherOpenChats", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class OpenChats(private val project: Project) : PersistentStateComponent<OpenChats.State>, Disposable {
    class Entry : BaseState() {
        var sessionId by string()
        var cwd by string()
    }

    class State : BaseState() {
        var chats by list<Entry>()
    }

    @Volatile private var state = State()
    @Volatile private var tracking = false
    @Volatile private var closing = false

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(ProjectCloseListener.TOPIC, object : ProjectCloseListener {
            override fun projectClosing(closed: Project) {
                if (closed !== project) return
                remember()
                closing = true
            }
        })
    }

    /** The saved sessions as (session id, folder), in tab order. */
    fun saved(): List<Pair<String, String>> =
        state.chats.mapNotNull { e -> e.sessionId?.let { id -> e.cwd?.let { id to it } } }

    /** From now on the list follows the open chats. Call once the restored tabs are open. */
    fun startTracking() {
        tracking = true
    }

    /** Called when the IDE saves: brings the list up to date first. */
    override fun getState(): State {
        remember()
        return state
    }

    override fun loadState(loaded: State) {
        state = loaded
        tracking = false
    }

    /** Copies the open chats that have a session (a chat gets one with its first message) into the state. */
    @Synchronized private fun remember() {
        if (!tracking || closing) return
        val open = project.serviceIfCreated<Chats>()?.all().orEmpty()
            .mapNotNull { c -> c.session.sessionId?.let { it to c.cwd } }
            .distinctBy { it.first }
        if (open == state.chats.map { it.sessionId to it.cwd }) return
        state.chats = open.map { (id, cwd) -> Entry().apply { sessionId = id; this.cwd = cwd } }.toMutableList()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): OpenChats = project.service()
    }
}
