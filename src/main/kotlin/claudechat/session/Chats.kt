package claudechat.session

import claudechat.cli.ClaudeBinary
import claudechat.cli.ClaudeCommand
import claudechat.cli.ClaudeProcess
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.git.GitWorktrees
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Claude Chat."
)

/** The project's open chats. Each chat owns its own claude process. */
@Service(Service.Level.PROJECT)
class Chats(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    var store: SessionStore = SessionStore.default()
    var worktrees: GitWorktrees = GitWorktrees(projectDir)

    private val chats = CopyOnWriteArrayList<Chat>()
    private val ids = AtomicInteger()

    val projectDir: String get() = project.basePath ?: System.getProperty("user.home")

    init {
        EditTracker.getInstance(project) // start recording saves before the first message
    }

    fun all(): List<Chat> = chats.toList()

    fun mainSpec(): ChatSpec = ChatSpec.inProject(projectDir)

    /** Creates and starts a chat (a resumed one shows its transcript). EDT. */
    fun open(spec: ChatSpec): Chat {
        val history = spec.resumeId?.let { store.transcript(spec.cwd, it) }.orEmpty()
        val chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { EditTracker.getInstance(project).consumeNote() },
            log = { LOG.info(it) },
        )
        chats += chat
        chat.start()
        return chat
    }

    /** Stops the chat's process. Its transcript stays on disk. */
    fun close(chat: Chat) {
        if (chats.remove(chat)) chat.dispose()
    }

    fun closeAll() = all().forEach(::close)

    private fun defaultMode(): PermissionMode? = PermissionMode.fromCli(ClaudeChatSettings.getInstance().state.defaultMode)

    private fun defaultConnect(launch: SessionLaunch): CliConnection {
        val settings = ClaudeChatSettings.getInstance().state
        val env = EnvironmentUtil.getEnvironmentMap()
        val binary = ClaudeBinary.resolve(settings.claudePath.orEmpty(), env["PATH"], System.getProperty("user.home")) {
            File(it).canExecute()
        } ?: throw ClaudeNotFoundException()
        val command = ClaudeCommand.build(binary, launch, ParametersListUtil.parse(settings.extraArgs.orEmpty()))
        return ClaudeProcess(command, File(launch.cwd), env)
    }

    override fun dispose() = closeAll()

    companion object {
        private val LOG = logger<Chats>()
        fun getInstance(project: Project): Chats = project.service()
    }
}
