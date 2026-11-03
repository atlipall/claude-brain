package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.CliEvent
import claudechat.cli.CliMessages
import claudechat.cli.CliParser
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.cli.str
import kotlinx.serialization.json.JsonObject

enum class SessionState { IDLE, RUNNING, AWAITING_PERMISSION, DEAD }

/** Called while the session lock is held: do not call back into the session synchronously. */
interface ChatListener {
    fun onItem(item: ChatItem)
    fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?)
}

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data object AllowForSession : PermissionDecision
    data class Deny(val message: String) : PermissionDecision
}

data class FileDiff(val filePath: String, val originalContent: String)

/** One conversation with one claude process (respawned with --resume on restart). Thread-safe. */
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    resumeId: String? = null,
    initialMode: PermissionMode? = null,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()

    var sessionId: String? = resumeId
        private set
    var mode: PermissionMode? = initialMode
        private set
    var state: SessionState = SessionState.IDLE
        private set

    private var connection: CliConnection? = null
    private var generation = 0
    private var seq = 0
    private var interruptRequested = false
    private val queue = ArrayDeque<String>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    private val streamed = HashMap<String, ChatItem.AssistantText>()
    private val streamedMessages = HashSet<String>()
    private val pendingPermissions = LinkedHashMap<String, CliEvent.PermissionRequest>()
    private val deniedToolUses = HashSet<String>()
    private val sessionAllowedTools = HashSet<String>()
    private val diffs = HashMap<String, FileDiff>()

    fun start() {
        synchronized(lock) {
            val gen = ++generation
            try {
                val conn = connect(SessionLaunch(sessionId, mode))
                connection = conn
                conn.start(
                    onLine = { line -> synchronized(lock) { if (gen == generation) handleLine(line) } },
                    onExit = { code, tail -> synchronized(lock) { if (gen == generation) handleExit(code, tail) } },
                )
                conn.send(CliMessages.initialize(nextId("init")))
            } catch (e: Exception) {
                connection = null
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            setState(SessionState.IDLE)
        }
    }

    /** Shows [text] in the chat; sends "[prefix]\n\n[text]" to Claude. Queued while a turn is running. */
    fun send(text: String, prefix: String? = null) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            emit(ChatItem.UserText(nextId("user"), text))
            val payload = if (prefix.isNullOrBlank()) text else "$prefix\n\n$text"
            if (state == SessionState.IDLE) {
                connection?.send(CliMessages.userText(payload))
                setState(SessionState.RUNNING)
            } else {
                queue.addLast(payload)
            }
        }
    }

    fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
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
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) setState(SessionState.RUNNING)
        }
    }

    fun setPermissionMode(newMode: PermissionMode) {
        synchronized(lock) {
            mode = newMode
            connection?.send(CliMessages.setPermissionMode(nextId("mode"), newMode.cli))
            notifyState()
        }
    }

    fun interrupt() {
        synchronized(lock) {
            if (state != SessionState.RUNNING && state != SessionState.AWAITING_PERMISSION) return
            interruptRequested = true
            queue.clear()
            for ((requestId, req) in pendingPermissions) {
                val toolId = req.toolUseId ?: "perm-$requestId"
                deniedToolUses += toolId
                connection?.send(CliMessages.deny(requestId, "Interrupted by the user."))
                toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
            }
            pendingPermissions.clear()
            connection?.send(CliMessages.interrupt(nextId("interrupt")))
        }
    }

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

    private fun handleLine(line: String) {
        for (event in CliParser.parse(line)) handle(event)
    }

    private fun handle(event: CliEvent) {
        when (event) {
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) sessionId = event.sessionId
                PermissionMode.fromCli(event.permissionMode)?.let { mode = it }
                notifyState()
            }
            is CliEvent.ControlResponse -> {
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
            }
            is CliEvent.BlockStop -> streamed.remove("${event.messageId}:${event.index}")?.let { emit(it.copy(streaming = false)) }
            is CliEvent.AssistantText ->
                if (event.messageId !in streamedMessages) emit(ChatItem.AssistantText(nextId("text"), event.text, false))
            is CliEvent.ToolUse -> {
                val base = toolCalls[event.toolUseId]
                    ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
                upsertTool(base.copy(name = event.name, input = event.input, filePath = filePathOf(event.input)))
            }
            is CliEvent.PermissionRequest -> onPermissionRequest(event)
            is CliEvent.ToolResult -> onToolResult(event)
            is CliEvent.UserText -> Unit
            is CliEvent.Result -> onResult(event)
            is CliEvent.Ignored -> Unit
            is CliEvent.Malformed -> log("Malformed line from claude: ${event.line.take(200)}")
        }
    }

    private fun onPermissionRequest(event: CliEvent.PermissionRequest) {
        if (event.toolName in sessionAllowedTools) {
            connection?.send(CliMessages.allow(event.requestId, event.input))
            return
        }
        pendingPermissions[event.requestId] = event
        val id = event.toolUseId ?: "perm-${event.requestId}"
        val base = toolCalls[id]
            ?: ChatItem.ToolCall(id, event.toolName, event.input, filePathOf(event.input), ToolStatus.RUNNING, null, null, false)
        upsertTool(base.copy(status = ToolStatus.AWAITING_PERMISSION, permissionRequestId = event.requestId))
        setState(SessionState.AWAITING_PERMISSION)
    }

    private fun onToolResult(event: CliEvent.ToolResult) {
        val tool = toolCalls[event.toolUseId] ?: return
        val status = when {
            event.toolUseId in deniedToolUses -> ToolStatus.DENIED
            event.isError -> ToolStatus.ERROR
            else -> ToolStatus.DONE
        }
        val diffPath = event.filePath ?: tool.filePath
        val hasDiff = event.hasOriginalFile && diffPath != null && !event.isError
        if (hasDiff) diffs[tool.id] = FileDiff(diffPath, event.originalFile ?: "")
        upsertTool(tool.copy(status = status, permissionRequestId = null, resultPreview = event.content.take(4000), hasDiff = hasDiff))
    }

    private fun onResult(event: CliEvent.Result) {
        pendingPermissions.clear()
        if (interruptRequested) {
            interruptRequested = false
            emit(ChatItem.Notice(nextId("notice"), "Interrupted", isError = false))
        } else {
            val text = if (event.isError) event.text ?: event.errors.joinToString("\n") else null
            emit(ChatItem.TurnResult(nextId("result"), event.isError, text, event.durationMs, event.costUsd))
        }
        val next = queue.removeFirstOrNull()
        if (next != null) {
            connection?.send(CliMessages.userText(next))
            setState(SessionState.RUNNING)
        } else {
            setState(SessionState.IDLE)
        }
    }

    private fun handleExit(code: Int, stderrTail: String) {
        connection = null
        pendingPermissions.clear()
        queue.clear()
        val detail = stderrTail.trim().takeLast(2000)
        val text = if (detail.isEmpty()) "Claude exited (code $code)." else "Claude exited (code $code).\n$detail"
        emit(ChatItem.Notice(nextId("notice"), text, isError = true))
        setState(SessionState.DEAD)
    }

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")
    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    private fun notifyState() = listener.onState(state, sessionId, mode)
}
