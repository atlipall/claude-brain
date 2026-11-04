package claudechat.session

import claudechat.acp.AcpSession
import claudechat.acp.JsonAcpClient
import claudechat.cli.ClaudeBinary
import claudechat.cli.ClaudeCommand
import claudechat.cli.ClaudeProcess
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.git.DiffBases
import claudechat.git.GitWorktrees
import claudechat.context.EditorContextService
import claudechat.ide.EditTracker
import claudechat.ide.TurnCheckpoints
import claudechat.ide.tools.ChatIdeHost
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.Disposable
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.io.FileFilter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Tether."
)

/** The project's open chats. Each chat owns its own claude (or ACP agent) process. */
@Service(Service.Level.PROJECT)
class Chats(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    /** Starts an ACP agent from its command line in a folder. */
    var acpConnector: (List<String>, String) -> CliConnection = ::defaultAcpConnect
    var store: SessionStore = SessionStore.default()
    var worktrees: GitWorktrees = GitWorktrees(projectDir)

    private val chats = CopyOnWriteArrayList<Chat>()
    private val ids = AtomicInteger()

    val projectDir: String get() = project.basePath ?: System.getProperty("user.home")

    // Cached: services must not be looked up while the project is being disposed (close runs then).
    private val tracker = EditTracker.getInstance(project) // also starts recording saves before the first message
    private val context = EditorContextService.getInstance(project) // also starts tracking the focused editor
    private val checkpoints = TurnCheckpoints.getInstance(project)

    fun all(): List<Chat> = chats.toList()

    fun mainSpec(): ChatSpec = ChatSpec.inProject(projectDir)

    /** The configured ACP agent's command line; null when none is set. */
    fun acpCommand(): List<String>? =
        ParametersListUtil.parse(ClaudeChatSettings.getInstance().state.acpCommand.orEmpty()).takeIf { it.isNotEmpty() }

    /** Creates and starts a chat (a resumed one shows its transcript). EDT. */
    fun open(spec: ChatSpec): Chat {
        val history = spec.resumeId?.let { store.transcript(spec.cwd, it) }.orEmpty()
        lateinit var chat: Chat
        val ide = ChatIdeHost(project, projectDir) { chat.cwd } // asked only once the chat runs
        chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { c -> listOfNotNull(checkpoints.consumeNote(c.id), tracker.consumeNote(c.id, c.cwd)).joinToString("\n\n").ifEmpty { null } },
            editorContext = { c -> if (ClaudeChatSettings.getInstance().state.attachEditorContext) context.contextFor(c.cwd) else null },
            resolveContext = { c, done ->
                if (ClaudeChatSettings.getInstance().state.attachEditorContext) context.resolve(c.cwd, done) else done(null)
            },
            log = { LOG.info(it) },
            startedAt = if (spec.resumeId == null) System.currentTimeMillis() else store.startedAt(spec.cwd, spec.resumeId),
            ide = ide,
            checkpoints = checkpoints,
            background = { AppExecutorUtil.getAppExecutorService().execute(it) },
            agent = spec.acp?.let { command ->
                { listener -> AcpSession({ JsonAcpClient(acpConnector(command, spec.cwd)) }, listener, spec.cwd, ChatSpec.agentName(command)) { LOG.info(it) } }
            },
        )
        tracker.register(chat.id)
        chats += chat
        // A resumed session started earlier: its base is found from startedAt. A new worktree is a branch (diffed against main).
        if (spec.resumeId == null && spec.worktree == null) {
            AppExecutorUtil.getAppExecutorService().execute { chat.startCommit = DiffBases().head(spec.cwd) }
        }
        chat.start()
        return chat
    }

    /** Stops the chat's process. Its transcript stays on disk. */
    fun close(chat: Chat) {
        if (chats.remove(chat)) {
            chat.dispose()
            tracker.forget(chat.id)
            checkpoints.forget(chat.id)
        }
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

    /** The agent gets the IDE's environment unchanged: its credentials and settings are its own. */
    private fun defaultAcpConnect(command: List<String>, cwd: String): CliConnection {
        val env = EnvironmentUtil.getEnvironmentMap()
        val name = command.first()
        val executable = if ('/' in name) name else PathEnvironmentVariableUtil.findInPath(name, env["PATH"], FileFilter { it.canExecute() })?.path
            ?: throw IllegalStateException("`$name` was not found on PATH. Set the ACP agent command, with the full path if needed, in Settings > Tools > Tether.")
        return ClaudeProcess(listOf(executable) + command.drop(1), File(cwd), env, stripped = emptyList())
    }

    override fun dispose() = closeAll()

    companion object {
        private val LOG = logger<Chats>()
        fun getInstance(project: Project): Chats = project.service()
    }
}
