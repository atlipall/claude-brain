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
import tether.cli.PermissionMode

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
        var mode by string()
        var model by string()
        var effort by string()
    }

    /** A saved chat: its session, folder, and how it was set up. */
    data class Saved(val sessionId: String, val cwd: String, val setup: SessionSetup = SessionSetup())

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

    /** The saved sessions in tab order; a mode this version doesn't know is dropped. */
    fun saved(): List<Saved> = state.chats.mapNotNull { e ->
        val id = e.sessionId ?: return@mapNotNull null
        val cwd = e.cwd ?: return@mapNotNull null
        Saved(id, cwd, SessionSetup(PermissionMode.fromCli(e.mode), e.model, e.effort))
    }

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
            .mapNotNull { c -> c.session.sessionId?.let { id -> Triple(id, c.cwd, c.session.setup) } }
            .distinctBy { it.first }
            .map { (id, folder, setup) ->
                Entry().apply {
                    sessionId = id
                    cwd = folder
                    mode = setup.mode?.cli
                    model = setup.model
                    effort = setup.effort
                }
            }
        if (open == state.chats) return
        state.chats = open.toMutableList()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): OpenChats = project.service()
    }
}
