package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.CliEvent
import claudechat.cli.CliMessages
import claudechat.cli.CliParser
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
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
}

/** Called while the session lock is held: do not call back into the session synchronously. */
interface ChatListener {
    fun onItem(item: ChatItem)
    fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?)

    /** The turn's activity, reported only when it changes; null when no turn is running. */
    fun onActivity(activity: Activity?) {}
}

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data object AllowForSession : PermissionDecision
    data class Deny(val message: String) : PermissionDecision
}

data class FileDiff(val filePath: String, val originalContent: String)

/** Longest base64 string accepted for a tool-result image (about 5 MB of bytes); longer ones show as "[image]". */
private const val MAX_TOOL_IMAGE_BASE64 = 7_000_000

/** Most images of one tool result converted to display copies; further ones show as "[image]". */
private const val MAX_TOOL_IMAGES = 6

/** One conversation with one claude process (respawned with --resume on restart). Thread-safe. */
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    cwd: String,
    resumeId: String? = null,
    initialMode: PermissionMode? = null,
    private val worktree: String? = null,
    private val log: (String) -> Unit = {},
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
    /** Lines for the CLI's stdin waiting for the running turn to end (image bytes live only here until sent). */
    private val queue = ArrayDeque<String>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    private val streamed = HashMap<String, ChatItem.AssistantText>()
    private val streamedMessages = HashSet<String>()
    private val pendingPermissions = LinkedHashMap<String, CliEvent.PermissionRequest>()
    private val deniedToolUses = HashSet<String>()
    private val sessionAllowedTools = HashSet<String>()
    private val diffs = HashMap<String, FileDiff>()
    /** Tool calls started and not yet answered (id to name), oldest first. */
    private val runningTools = LinkedHashMap<String, String>()
    /** The phase of the running turn, null between turns; [Activity.Waiting] is derived from the open requests. */
    private var phase: Activity? = null
    private var reported: Activity? = null

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
                conn.send(CliMessages.initialize(nextId("init")))
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
     * Shows [text] (with [thumbs] above it) in the chat; sends "[prefix]\n\n[text]" and [images] to Claude. Queued
     * while a turn is running.
     */
    fun send(text: String, prefix: String? = null, images: List<Image> = emptyList(), thumbs: List<String> = emptyList()) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            emit(ChatItem.UserText(nextId("user"), text, thumbs))
            val payload = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotEmpty() }).joinToString("\n\n")
            val line = CliMessages.userContent(payload, images)
            if (state == SessionState.IDLE) {
                connection?.send(line)
                phase = Activity.Starting
                setState(SessionState.RUNNING)
                reportActivity()
            } else {
                queue.addLast(line)
            }
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
                phase = if (decision is PermissionDecision.Deny) Activity.Thinking else Activity.Running(req.toolName)
                setState(SessionState.RUNNING)
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
                phase = Activity.Running(req.toolName)
                setState(SessionState.RUNNING)
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
            if (pendingPermissions.isNotEmpty()) phase = Activity.Thinking
            pendingPermissions.clear()
            connection?.send(CliMessages.interrupt(nextId("interrupt")))
            reportActivity()
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
            is CliEvent.ThinkingStart -> { phase = Activity.Thinking; reportActivity() }
            is CliEvent.BlockStop -> streamed.remove("${event.messageId}:${event.index}")?.let { emit(it.copy(streaming = false)) }
            is CliEvent.AssistantText ->
                if (event.messageId !in streamedMessages) emit(ChatItem.AssistantText(nextId("text"), event.text, false))
            is CliEvent.ToolUse -> {
                val base = toolCalls[event.toolUseId]
                    ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
                upsertTool(base.copy(name = event.name, input = event.input, filePath = filePathOf(event.input)))
                runningTools[event.toolUseId] = event.name
                phase = Activity.Running(event.name)
                reportActivity()
            }
            is CliEvent.PermissionRequest -> onPermissionRequest(event)
            is CliEvent.ToolResult -> onToolResult(event, copies.orEmpty())
            is CliEvent.UserText -> Unit
            is CliEvent.Result -> onResult(event)
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
        if (!isQuestion && event.toolName in sessionAllowedTools) {
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

    private fun onToolResult(event: CliEvent.ToolResult, copies: List<String>) {
        runningTools.remove(event.toolUseId)
        phase = runningTools.entries.lastOrNull()?.let { Activity.Running(it.value) } ?: Activity.Thinking
        reportActivity()
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
        val next = queue.removeFirstOrNull()
        if (next != null) {
            connection?.send(next)
            phase = Activity.Starting
            setState(SessionState.RUNNING)
        } else {
            setState(SessionState.IDLE)
        }
        reportActivity()
    }

    private fun handleExit(code: Int, stderrTail: String) {
        connection = null
        pendingPermissions.clear()
        runningTools.clear()
        queue.clear()
        phase = null
        val detail = stderrTail.trim().takeLast(2000)
        val text = if (detail.isEmpty()) "Claude exited (code $code)." else "Claude exited (code $code).\n$detail"
        emit(ChatItem.Notice(nextId("notice"), text, isError = true))
        setState(SessionState.DEAD)
        reportActivity()
    }

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")
    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    /** Tells the listener the current activity if it differs from the last one told. */
    private fun reportActivity() {
        val now = if (state == SessionState.RUNNING || state == SessionState.AWAITING_PERMISSION) {
            if (pendingPermissions.isNotEmpty()) Activity.Waiting else phase
        } else null
        if (now == reported) return
        reported = now
        listener.onActivity(now)
    }
    private fun notifyState() = listener.onState(state, sessionId, mode)
}
