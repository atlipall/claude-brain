package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.CliEvent
import claudechat.cli.CliMessages
import claudechat.cli.CliParser
import claudechat.cli.ContextUsage
import claudechat.cli.HookRegistration
import claudechat.cli.McpServer
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.cli.obj
import claudechat.cli.str
import claudechat.cli.ImageBlock
import claudechat.image.Image
import claudechat.image.Images
import kotlinx.serialization.json.JsonObject
import java.util.Base64

enum class SessionState { IDLE, RUNNING, AWAITING_PERMISSION, DEAD }

/** What Claude is doing during a turn, as far as the CLI's events tell. */
sealed interface Activity {
    /** The message was handed over; nothing has come back yet. */
    data object Starting : Activity
    data object Thinking : Activity
    data class Running(val tool: String) : Activity
    data object Writing : Activity
    /** A permission request or question is open. */
    data object Waiting : Activity
    /** No turn is running, but [count] background agents are still working. */
    data class Agents(val count: Int) : Activity
}

/** Called while the session lock is held: do not call back into the session synchronously. */
interface ChatListener {
    fun onItem(item: ChatItem)
    fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?)

    /** The turn's activity, reported only when it changes; null when nothing is running (no turn, no background agent). */
    fun onActivity(activity: Activity?) {}

    /** How full the context window is (the latest report). */
    fun onContext(usage: ContextUsage) {}

    /** A predicted next prompt, told only while the session is idle. */
    fun onSuggestion(text: String) {}

    /** The messages waiting for the running turn to end, oldest first (every change). */
    fun onQueue(messages: List<QueuedMessage>) {}

    /** Queued messages that will not be sent (the turn was stopped or the process died), oldest first. */
    fun onDropped(messages: List<QueuedMessage>) {}
}

/** A message sent while a turn was running; [images] are thumbnails as for [ChatItem.UserText]. */
data class QueuedMessage(val id: String, val text: String, val images: List<String>)

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data object AllowForSession : PermissionDecision
    data class Deny(val message: String) : PermissionDecision
}

data class FileDiff(val filePath: String, val originalContent: String)

/** Longest base64 string accepted for a tool-result image (about 5 MB of bytes); longer ones show as "[image]". */
private const val MAX_TOOL_IMAGE_BASE64 = 7_000_000

/** Name of the in-process MCP server that serves [IdeHost.tools]; Claude sees its tools as `mcp__tether__<name>`. */
internal const val IDE_SERVER = "tether"
private const val IDE_TOOL_PREFIX = "mcp__${IDE_SERVER}__"

/** Callback id of the PostToolUse hook that runs [IdeHost.afterEdit]. */
private const val AFTER_EDIT = "after-edit"
private const val POST_TOOL_USE = "PostToolUse"
private val EDIT_TOOLS = HookRegistration("Edit|Write|MultiEdit|NotebookEdit", AFTER_EDIT, timeoutSeconds = 60)

/** Prefix of get_context_usage request ids, so their replies are recognised even after a newer request replaced them. */
private const val CONTEXT_PREFIX = "context"

/** Most images of one tool result converted to display copies; further ones show as "[image]". */
private const val MAX_TOOL_IMAGES = 6

/** Longest agent prompt or report kept; longer ones are cut and end with "…". */
internal const val MAX_AGENT_TEXT = 100_000

/** The call a subagent ends with; its input is the report, which the card shows from the task events instead. */
private const val HANDBACK = "SubagentHandback"

private fun capped(text: String?) = text?.let { if (it.length > MAX_AGENT_TEXT) it.take(MAX_AGENT_TEXT) + "\u2026" else it }

/** Longest prompt suggestion passed on; longer ones are dropped rather than cut, since a cut prompt is a wrong one. */
internal const val MAX_SUGGESTION_CHARS = 2000

/** One conversation with one claude process (respawned with --resume on restart). Thread-safe. */
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    cwd: String,
    resumeId: String? = null,
    initialMode: PermissionMode? = null,
    private val worktree: String? = null,
    private val log: (String) -> Unit = {},
    /** Tools and edit checks the IDE lends Claude; null lends none. */
    private val ide: IdeHost? = null,
    /** Runs IDE work (tool calls, edit checks) off the CLI reader thread. */
    private val background: (Runnable) -> Unit = { it.run() },
) {
    private val lock = Any()

    var sessionId: String? = resumeId
        private set
    var mode: PermissionMode? = initialMode
        private set
    var state: SessionState = SessionState.IDLE
        private set

    /** The folder claude runs in: the launch directory until system/init reports the real one (a new worktree's path). */
    @Volatile var cwd: String = cwd
        private set
    /** True once system/init reported a cwd; from then on restarts run there instead of creating the worktree again. */
    @Volatile var cwdReported = false
        private set

    private var connection: CliConnection? = null
    private var generation = 0
    private var seq = 0
    private var interruptRequested = false
    /** A queued message and its line for the CLI's stdin (image bytes live only here until sent). */
    private class Queued(val shown: QueuedMessage, val line: String)
    /** Messages waiting for the running turn to end, oldest first. */
    private val queue = ArrayDeque<Queued>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    private val streamed = HashMap<String, ChatItem.AssistantText>()
    private val streamedMessages = HashSet<String>()
    /** The initialize request of the current process; context usage is asked for only once it has been answered. */
    private var initRequestId: String? = null
    /** The latest get_context_usage request; replies to older ones are stale and dropped. */
    private var contextRequestId: String? = null
    private val pendingPermissions = LinkedHashMap<String, CliEvent.PermissionRequest>()
    private val deniedToolUses = HashSet<String>()
    private val sessionAllowedTools = HashSet<String>()
    private val diffs = HashMap<String, FileDiff>()
    /** What is known about each subagent, by its Agent call's id (task events may arrive before the call's item). */
    private val agents = HashMap<String, AgentInfo>()
    /** Tool calls started and not yet answered (id to name), oldest first. */
    private val runningTools = LinkedHashMap<String, String>()
    /** The phase of the running turn, null between turns; [Activity.Waiting] is derived from the open requests. */
    private var phase: Activity? = null
    private var reported: Activity? = null
    /** Background tasks the CLI reports running; they outlive the turn that started them. */
    private var backgroundTasks = 0
    /** Serves [IdeHost.tools] to the current process; null when it was started without them. */
    private var mcpServer: McpServer? = null
    /** Full names (`mcp__tether__<name>`) of the current process's read-only IDE tools, allowed without asking. */
    private var readOnlyTools: Set<String> = emptySet()

    fun start() {
        synchronized(lock) {
            val gen = ++generation
            var created: CliConnection? = null
            try {
                val conn = connect(SessionLaunch(sessionId, mode, cwd, if (cwdReported) null else worktree))
                created = conn
                connection = conn
                conn.start(
                    onLine = { line ->
                        // Parsing and image decoding happen before the lock is taken: they can be slow.
                        val events = CliParser.parse(line).map { it to displayCopiesOf(it) }
                        synchronized(lock) { if (gen == generation) for ((event, copies) in events) handle(event, copies) }
                    },
                    onExit = { code, tail -> synchronized(lock) { if (gen == generation) handleExit(code, tail) } },
                )
                val initId = nextId("init")
                initRequestId = initId
                val tools = ide?.tools.orEmpty()
                mcpServer = if (tools.isEmpty()) null else McpServer(IDE_SERVER, tools)
                readOnlyTools = tools.filter { it.readOnly }.mapTo(HashSet()) { IDE_TOOL_PREFIX + it.name }
                val hooks = if (ide?.checksEdits == true) mapOf(POST_TOOL_USE to listOf(EDIT_TOOLS)) else emptyMap()
                conn.send(CliMessages.initialize(initId, listOfNotNull(mcpServer?.let { IDE_SERVER }), hooks))
            } catch (e: Exception) {
                generation++
                created?.let { runCatching { it.stop() } }
                connection = null
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            setState(SessionState.IDLE)
        }
    }

    /**
     * Shows [text] (with [thumbs] above it) in the chat; sends "[prefix]\n\n[text]" and [images] to Claude. While a
     * turn is running the message is queued instead (see [ChatListener.onQueue]) and enters the chat when it goes out.
     */
    fun send(text: String, prefix: String? = null, images: List<Image> = emptyList(), thumbs: List<String> = emptyList()) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            val payload = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotEmpty() }).joinToString("\n\n")
            val line = CliMessages.userContent(payload, images)
            if (state == SessionState.IDLE) {
                emit(ChatItem.UserText(nextId("user"), text, thumbs))
                connection?.send(line)
                beginRunning(Activity.Starting)
                reportActivity()
            } else {
                queue.addLast(Queued(QueuedMessage(nextId("queued"), text, thumbs), line))
                notifyQueue()
            }
        }
    }

    /** Takes queued message [id] out of the queue without sending it; null when it is not queued (any more). */
    fun unqueue(id: String): QueuedMessage? = synchronized(lock) {
        val i = queue.indexOfFirst { it.shown.id == id }
        if (i < 0) return null
        val removed = queue.removeAt(i).shown
        notifyQueue()
        removed
    }

    /** Stops the running turn and sends queued message [id] next; the rest of the queue follows it. */
    fun sendNow(id: String) {
        synchronized(lock) {
            if (!turnOpen) return
            val i = queue.indexOfFirst { it.shown.id == id }
            if (i < 0) return
            if (i > 0) {
                queue.addFirst(queue.removeAt(i))
                notifyQueue()
            }
            if (!interruptRequested) stopTurn()
        }
    }

    /** Shows a notice in the chat (e.g. an image that could not be attached). Any thread. */
    fun notice(text: String, isError: Boolean = true) {
        synchronized(lock) { emit(ChatItem.Notice(nextId("notice"), text, isError)) }
    }

    fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
            val pending = pendingPermissions[requestId] ?: return
            // A question is answered with answer() or declined; it can't be allowed without answers.
            if (pending.toolName == ASK_USER_QUESTION && decision !is PermissionDecision.Deny) return
            val req = pendingPermissions.remove(requestId) ?: return
            val toolId = req.toolUseId ?: "perm-$requestId"
            when (decision) {
                PermissionDecision.Allow -> connection?.send(CliMessages.allow(requestId, req.input))
                PermissionDecision.AllowForSession -> {
                    sessionAllowedTools += req.toolName
                    connection?.send(CliMessages.allow(requestId, req.input))
                }
                is PermissionDecision.Deny -> {
                    deniedToolUses += toolId
                    connection?.send(CliMessages.deny(requestId, decision.message))
                }
            }
            val status = if (decision is PermissionDecision.Deny) ToolStatus.DENIED else ToolStatus.RUNNING
            toolCalls[toolId]?.let { upsertTool(it.copy(status = status, permissionRequestId = null)) }
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) {
                val subagent = toolCalls[toolId]?.parentId != null
                beginRunning(if (decision is PermissionDecision.Deny || subagent) toolPhase() else Activity.Running(req.toolName))
            }
            reportActivity()
        }
    }

    /**
     * Answers a pending AskUserQuestion request. False (and nothing sent) when [requestId] is not a pending question
     * or [answers] don't match its questions and options (see [Questions.answerTexts]).
     */
    fun answer(requestId: String, answers: Map<String, QuestionAnswer>): Boolean {
        synchronized(lock) {
            val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return false
            val toolId = req.toolUseId ?: "perm-$requestId"
            val card = toolCalls[toolId]?.question ?: return false
            val texts = Questions.answerTexts(card.questions, answers)
            if (texts == null) { answerFailed(requestId); return false }
            pendingPermissions.remove(requestId)
            connection?.send(CliMessages.allow(requestId, Questions.withAnswers(req.input, texts)))
            toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.RUNNING, permissionRequestId = null, question = card.copy(answers = texts))) }
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) {
                beginRunning(Activity.Thinking)
            }
            reportActivity()
            return true
        }
    }

    /** Tells the user an answer was refused and re-sends the pending question item so the page re-enables its Submit. */
    fun answerFailed(requestId: String) {
        synchronized(lock) {
            val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return
            emit(ChatItem.Notice(nextId("notice"), "Couldn't send the answer \u2014 please try again or Skip.", isError = true))
            toolCalls[req.toolUseId ?: "perm-$requestId"]?.let { emit(it) }
        }
    }

    /** The questions of the pending AskUserQuestion request [requestId], or null when there is none. */
    fun pendingQuestions(requestId: String): List<Question>? = synchronized(lock) {
        val req = pendingPermissions[requestId]?.takeIf { it.toolName == ASK_USER_QUESTION } ?: return null
        toolCalls[req.toolUseId ?: "perm-$requestId"]?.question?.questions
    }

    fun setPermissionMode(newMode: PermissionMode) {
        synchronized(lock) {
            mode = newMode
            connection?.send(CliMessages.setPermissionMode(nextId("mode"), newMode.cli))
            notifyState()
        }
    }

    /** Stops the running turn; queued messages are dropped (see [ChatListener.onDropped]). */
    fun interrupt() {
        synchronized(lock) {
            if (!turnOpen) return
            dropQueue()
            if (!interruptRequested) stopTurn()
        }
    }

    private fun stopTurn() {
        interruptRequested = true
        for ((requestId, req) in pendingPermissions) {
            val toolId = req.toolUseId ?: "perm-$requestId"
            deniedToolUses += toolId
            connection?.send(CliMessages.deny(requestId, "Interrupted by the user."))
            toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
        }
        if (pendingPermissions.isNotEmpty()) phase = Activity.Thinking
        pendingPermissions.clear()
        connection?.send(CliMessages.interrupt(nextId("interrupt")))
        reportActivity()
    }

    private fun dropQueue() {
        if (queue.isEmpty()) return
        val dropped = queue.map { it.shown }
        queue.clear()
        notifyQueue()
        listener.onDropped(dropped)
        val images = dropped.sumOf { it.images.size }
        if (images > 0) emit(ChatItem.Notice(nextId("notice"), "$images queued image${if (images == 1) " was" else "s were"} not sent.", isError = false))
    }

    private val turnOpen get() = state == SessionState.RUNNING || state == SessionState.AWAITING_PERMISSION

    private fun notifyQueue() = listener.onQueue(queue.map { it.shown })

    /** Respawns a dead session with --resume and the current permission mode. */
    fun restart() {
        synchronized(lock) {
            if (state != SessionState.DEAD) return
            start()
        }
    }

    fun dispose() {
        synchronized(lock) {
            generation++
            connection?.stop()
            connection = null
        }
    }

    fun diffFor(toolUseId: String): FileDiff? = synchronized(lock) { diffs[toolUseId] }

    /** Display copies for a tool result's images: at most [MAX_TOOL_IMAGES] are converted, the rest show as "[image]". */
    private fun displayCopiesOf(event: CliEvent): List<String>? =
        (event as? CliEvent.ToolResult)?.images?.mapIndexed { i, block -> if (i < MAX_TOOL_IMAGES) displayCopy(block) else "" }

    private fun handle(event: CliEvent, copies: List<String>? = null) {
        when (event) {
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) sessionId = event.sessionId
                PermissionMode.fromCli(event.permissionMode)?.let { mode = it }
                event.cwd?.takeIf { it.isNotBlank() }?.let { cwd = it; cwdReported = true }
                notifyState()
            }
            is CliEvent.ControlResponse -> {
                // Failures are only logged: an older CLI may not know the request.
                if (event.requestId.startsWith(CONTEXT_PREFIX)) {
                    if (event.requestId != contextRequestId) return
                    contextRequestId = null
                    if (event.isSuccess) event.response?.let(ContextUsage::parse)?.let(listener::onContext)
                    else log("Context usage request failed: ${event.error}")
                    return
                }
                if (event.requestId == initRequestId) {
                    initRequestId = null
                    if (event.isSuccess) requestContextUsage()
                }
                if (!event.isSuccess) {
                    emit(ChatItem.Notice(nextId("notice"), "Claude rejected a request: ${event.error ?: "unknown error"}", isError = true))
                } else {
                    val reported = event.response?.let { it.str("current_permission_mode") ?: it.str("mode") }
                    PermissionMode.fromCli(reported)?.let { mode = it; notifyState() }
                }
            }
            is CliEvent.TextDelta -> {
                val key = "${event.messageId}:${event.index}"
                streamedMessages += event.messageId
                val current = streamed[key] ?: ChatItem.AssistantText("text-$key", "", true)
                val next = current.copy(text = current.text + event.text, streaming = true)
                streamed[key] = next
                emit(next)
                phase = Activity.Writing
                reportActivity()
            }
            is CliEvent.MessageStart ->
                // The CLI starts a turn by itself, e.g. to read a finished background agent's report.
                if (state == SessionState.IDLE) {
                    beginRunning(Activity.Thinking)
                    reportActivity()
                }
            is CliEvent.BackgroundTasks -> { backgroundTasks = event.count; reportActivity() }
            is CliEvent.ThinkingStart -> { phase = Activity.Thinking; reportActivity() }
            is CliEvent.BlockStop -> streamed.remove("${event.messageId}:${event.index}")?.let { emit(it.copy(streaming = false)) }
            is CliEvent.AssistantText -> when {
                // A subagent's messages arrive whole; only the main conversation's are streamed.
                event.parentId != null -> emit(ChatItem.AssistantText(nextId("text"), event.text, false, event.parentId))
                event.messageId !in streamedMessages -> emit(ChatItem.AssistantText(nextId("text"), event.text, false))
            }
            is CliEvent.ToolUse -> {
                if (event.parentId != null && event.name == HANDBACK) return
                val base = toolCalls[event.toolUseId]
                    ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
                upsertTool(base.copy(
                    name = event.name, input = event.input, filePath = filePathOf(event.input),
                    parentId = event.parentId ?: base.parentId, agent = agents[event.toolUseId],
                ))
                // The strip follows the main conversation; a subagent's tools show in its card.
                if (event.parentId == null) {
                    runningTools[event.toolUseId] = event.name
                    phase = Activity.Running(event.name)
                    reportActivity()
                }
            }
            is CliEvent.TaskStarted -> updateAgent(event.toolUseId) {
                it.copy(subagentType = event.subagentType, background = event.background, prompt = capped(event.prompt))
            }
            is CliEvent.TaskProgress -> updateAgent(event.toolUseId) {
                it.copy(toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs, lastTool = event.lastTool)
            }
            is CliEvent.TaskDone -> updateAgent(event.toolUseId) {
                it.copy(
                    state = if (event.status == "completed") AgentState.DONE else AgentState.FAILED,
                    toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs,
                    lastTool = null, report = capped(event.report) ?: it.report,
                )
            }
            is CliEvent.PermissionRequest -> onPermissionRequest(event)
            is CliEvent.McpMessage -> onMcpMessage(event)
            is CliEvent.HookCallback -> onHookCallback(event)
            is CliEvent.ToolResult -> onToolResult(event, copies.orEmpty())
            is CliEvent.UserText -> Unit
            is CliEvent.Result -> onResult(event)
            is CliEvent.PromptSuggestion -> onSuggestion(event.text)
            is CliEvent.Ignored -> Unit
            is CliEvent.Malformed -> log("Malformed line from claude: ${event.line.take(200)}")
        }
    }

    private fun onPermissionRequest(event: CliEvent.PermissionRequest) {
        val isQuestion = event.toolName == ASK_USER_QUESTION
        val questions = if (isQuestion) Questions.parse(event.input) else null
        if (isQuestion && questions == null) {
            connection?.send(CliMessages.deny(event.requestId, "The question was malformed."))
            deniedToolUses += event.toolUseId ?: "perm-${event.requestId}"
            emit(ChatItem.Notice(nextId("notice"), "Claude asked a question that could not be shown.", isError = true))
            return
        }
        if (!isQuestion && (event.toolName in sessionAllowedTools || event.toolName in readOnlyTools)) {
            connection?.send(CliMessages.allow(event.requestId, event.input))
            return
        }
        pendingPermissions[event.requestId] = event
        val id = event.toolUseId ?: "perm-${event.requestId}"
        val base = toolCalls[id]
            ?: ChatItem.ToolCall(id, event.toolName, event.input, filePathOf(event.input), ToolStatus.RUNNING, null, null, false)
        upsertTool(base.copy(
            status = ToolStatus.AWAITING_PERMISSION,
            permissionRequestId = event.requestId,
            question = questions?.let { QuestionCard(it) },
        ))
        setState(SessionState.AWAITING_PERMISSION)
        reportActivity()
    }

    /** Answers Claude's MCP traffic to [IDE_SERVER]; tool calls run in [background] and answer the process that asked. */
    private fun onMcpMessage(event: CliEvent.McpMessage) {
        val conn = connection ?: return
        val server = mcpServer?.takeIf { event.server == IDE_SERVER }
        when {
            server == null -> conn.send(CliMessages.controlError(event.requestId, "No MCP server named ${event.server}"))
            !server.isRequest(event.message) -> conn.send(CliMessages.mcpResponse(event.requestId, McpServer.ACK))
            else -> background { conn.send(CliMessages.mcpResponse(event.requestId, server.reply(event.message))) }
        }
    }

    /** Runs [IdeHost.afterEdit] for the file an edit tool wrote; the CLI waits for every callback, so one is always sent. */
    private fun onHookCallback(event: CliEvent.HookCallback) {
        val conn = connection ?: return
        val host = ide
        val toolInput = event.input.obj("tool_input")
        val path = toolInput?.let { it.str("file_path") ?: it.str("notebook_path") }
        if (event.callbackId != AFTER_EDIT || host == null || path == null) {
            conn.send(CliMessages.hookResponse(event.requestId, POST_TOOL_USE, null))
            return
        }
        background {
            var note: String? = null
            try {
                note = host.afterEdit(path)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                log("After-edit check failed for $path: $e")
            } finally {
                conn.send(CliMessages.hookResponse(event.requestId, POST_TOOL_USE, note))
            }
        }
    }

    private fun onToolResult(event: CliEvent.ToolResult, copies: List<String>) {
        if (event.parentId == null) {
            runningTools.remove(event.toolUseId)
            phase = toolPhase()
            reportActivity()
        }
        val tool = toolCalls[event.toolUseId] ?: return
        val status = when {
            event.toolUseId in deniedToolUses -> ToolStatus.DENIED
            event.isError -> ToolStatus.ERROR
            else -> ToolStatus.DONE
        }
        val diffPath = event.filePath ?: tool.filePath
        val hasDiff = event.hasOriginalFile && diffPath != null && !event.isError
        if (hasDiff) diffs[tool.id] = FileDiff(diffPath, event.originalFile ?: "")
        upsertTool(tool.copy(
            status = status,
            permissionRequestId = null,
            resultPreview = event.content.take(4000),
            hasDiff = hasDiff,
            images = copies,
        ))
    }

    /**
     * A display copy (at most 800 px and 512 KB) of an image the CLI returned, as a `data:` URL; "" when the block
     * is not a usable image. The CLI's claimed media type and its raw base64 never reach the page: the bytes are
     * decoded, sniffed and re-encoded by [Images.preview], and the URL is built from the re-encoded type.
     * Runs on the CLI reader thread, outside the session lock.
     */
    private fun displayCopy(block: ImageBlock): String {
        if (block.data.length > MAX_TOOL_IMAGE_BASE64) return ""
        val bytes = try { Base64.getDecoder().decode(block.data) } catch (e: IllegalArgumentException) { return "" }
        if (bytes.size > Images.MAX_INPUT_BYTES) return ""
        return Images.preview(bytes)?.dataUrl().orEmpty()
    }

    private fun onResult(event: CliEvent.Result) {
        // A foreground agent ends within its turn; one still running now was cut off (e.g. interrupted).
        endAgents { !it.background }
        pendingPermissions.clear()
        runningTools.clear()
        phase = null
        if (interruptRequested) {
            interruptRequested = false
            emit(ChatItem.Notice(nextId("notice"), "Interrupted", isError = false))
        } else {
            val text = if (event.isError) event.text ?: event.errors.joinToString("\n") else null
            emit(ChatItem.TurnResult(nextId("result"), event.isError, text, event.durationMs, event.costUsd))
        }
        requestContextUsage()
        val next = queue.removeFirstOrNull()
        if (next != null) {
            notifyQueue()
            emit(ChatItem.UserText(nextId("user"), next.shown.text, next.shown.images))
            connection?.send(next.line)
            beginRunning(Activity.Starting)
        } else {
            setState(SessionState.IDLE)
        }
        reportActivity()
    }

    /** A suggestion that arrives once the next turn has begun is stale, so it is dropped. */
    private fun onSuggestion(raw: String) {
        val text = raw.trim()
        if (state != SessionState.IDLE || text.isEmpty() || text.length > MAX_SUGGESTION_CHARS) return
        listener.onSuggestion(text)
    }

    private fun handleExit(code: Int, stderrTail: String) {
        connection = null
        mcpServer = null
        readOnlyTools = emptySet()
        initRequestId = null
        contextRequestId = null
        pendingPermissions.clear()
        runningTools.clear()
        dropQueue()
        backgroundTasks = 0
        endAgents { true }
        phase = null
        val detail = stderrTail.trim().takeLast(2000)
        val text = if (detail.isEmpty()) "Claude exited (code $code)." else "Claude exited (code $code).\n$detail"
        emit(ChatItem.Notice(nextId("notice"), text, isError = true))
        setState(SessionState.DEAD)
        reportActivity()
    }

    private fun updateAgent(toolUseId: String, change: (AgentInfo) -> AgentInfo) {
        val old = agents[toolUseId] ?: AgentInfo(null, false, null, AgentState.RUNNING)
        val info = change(old)
        if (info == old && toolUseId in agents) return
        agents[toolUseId] = info
        toolCalls[toolUseId]?.let { upsertTool(it.copy(agent = info)) }
    }

    /** Marks running agents that [cutOff] picks as failed: no task_notification will come for them. */
    private fun endAgents(cutOff: (AgentInfo) -> Boolean) {
        for ((id, info) in agents.entries.toList()) {
            if (info.state == AgentState.RUNNING && cutOff(info)) updateAgent(id) { it.copy(state = AgentState.FAILED, lastTool = null) }
        }
    }

    /** The phase while the main conversation's tools run: the newest running tool, or thinking when none is. */
    private fun toolPhase(): Activity = runningTools.entries.lastOrNull()?.let { Activity.Running(it.value) } ?: Activity.Thinking

    private fun requestContextUsage() {
        val conn = connection ?: return
        val id = nextId(CONTEXT_PREFIX)
        contextRequestId = id
        conn.send(CliMessages.getContextUsage(id))
    }

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")
    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    /** Enters RUNNING with [phase] set first: a running session always has an activity (Chat.busy relies on it). */
    private fun beginRunning(phase: Activity) { this.phase = phase; setState(SessionState.RUNNING) }
    /** Tells the listener the current activity if it differs from the last one told. */
    private fun reportActivity() {
        val now = if (turnOpen) {
            if (pendingPermissions.isNotEmpty()) Activity.Waiting else phase
        } else if (state == SessionState.IDLE && backgroundTasks > 0) Activity.Agents(backgroundTasks) else null
        if (now == reported) return
        reported = now
        listener.onActivity(now)
    }
    private fun notifyState() = listener.onState(state, sessionId, mode)
}
