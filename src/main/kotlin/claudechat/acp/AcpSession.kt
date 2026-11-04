package claudechat.acp

import claudechat.cli.ContextUsage
import claudechat.image.Image
import claudechat.session.Activity
import claudechat.session.AgentSession
import claudechat.session.ChatItem
import claudechat.session.ChatListener
import claudechat.session.Choice
import claudechat.session.FileDiff
import claudechat.session.PermissionDecision
import claudechat.session.QueuedMessage
import claudechat.session.SessionState
import claudechat.session.ToolStatus
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * One chat with an ACP agent in [cwd]. Each start makes a new client (one agent process); a restart loads the same
 * session where the agent can. [name] names the agent in notices. Thread-safe.
 */
class AcpSession(
    private val newClient: () -> AcpClient,
    private val listener: ChatListener,
    override val cwd: String,
    private val name: String,
    private val log: (String) -> Unit = {},
) : AgentSession {
    private val lock = Any()

    override var sessionId: String? = null
        private set
    override var modeId: String? = null
        private set
    override var state: SessionState = SessionState.IDLE
        private set

    private var client: AcpClient? = null
    private var agent: AgentInfo? = null
    private var generation = 0
    private var seq = 0
    private var modes: List<Choice> = emptyList()
    /** True once the session is open; messages sent before wait in the queue. */
    private var ready = false
    /** True while session/load replays the history, which the chat already shows. */
    private var loading = false
    /** The running prompt; null between turns. */
    private var turn: CompletableFuture<String>? = null
    private var turnStarted = 0L
    private var interruptRequested = false

    private class Queued(val shown: QueuedMessage, val text: String, val images: List<Image>)
    private val queue = ArrayDeque<Queued>()
    /** [live]: the card's tool call was already running when the agent asked. */
    private class Pending(val request: PermissionRequest, val toolId: String, val live: Boolean)
    private val pending = LinkedHashMap<String, Pending>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    /** Tool calls started and not yet finished (id to title), oldest first. */
    private val runningTools = LinkedHashMap<String, String>()
    private var streaming: ChatItem.AssistantText? = null
    private var streamingMessage: String? = null
    private var phase: Activity? = null
    private var reported: Activity? = null

    private val prompting get() = turn != null

    override fun start() {
        synchronized(lock) {
            val gen = ++generation
            val c = try {
                newClient()
            } catch (e: Exception) {
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            client = c
            ready = false
            listener.onModes(modes)
            setState(SessionState.IDLE)
            c.connect(events(gen))
                .thenCompose { info -> synchronized(lock) { open(c, info) } }
                .whenComplete { opened, error -> synchronized(lock) { if (gen == generation) opened(c, opened, error) } }
        }
    }

    private fun open(c: AcpClient, info: AgentInfo): CompletableFuture<SessionOpened> {
        agent = info
        val previous = sessionId ?: return c.newSession(cwd)
        if (!info.loadSession) {
            emit(ChatItem.Notice(nextId("notice"), "$name can't reopen its sessions; this is a new session.", isError = false))
            return c.newSession(cwd)
        }
        loading = true
        return c.loadSession(previous, cwd)
    }

    private fun opened(c: AcpClient, opened: SessionOpened?, error: Throwable?) {
        val reloaded = loading
        loading = false
        if (opened == null) {
            generation++ // the closing client's exit is not a second failure
            c.close()
            client = null
            emit(ChatItem.Notice(nextId("notice"), "Couldn't start $name: ${messageOf(error)}", isError = true))
            dropQueue()
            setState(SessionState.DEAD)
            return
        }
        sessionId = opened.sessionId
        modes = opened.modes
        modeId = opened.currentMode
        ready = true
        listener.onModes(modes)
        if (reloaded) emit(ChatItem.Notice(nextId("notice"), "Reconnected to the session.", isError = false))
        sendNext()
    }

    private fun events(gen: Int) = object : AcpListener {
        override fun onUpdate(sessionId: String, update: AcpUpdate) {
            synchronized(lock) { if (gen == generation && !loading) handle(update) }
        }

        override fun onPermission(request: PermissionRequest) {
            synchronized(lock) { if (gen == generation) ask(request) else request.cancel() }
        }

        override fun onClosed(exitCode: Int?, detail: String) {
            synchronized(lock) { if (gen == generation) closed(exitCode, detail) }
        }
    }

    override fun send(text: String, prefix: String?, images: List<Image>, thumbs: List<String>) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            val payload = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, text.takeIf { it.isNotEmpty() }).joinToString("\n\n")
            val message = Queued(QueuedMessage(nextId("queued"), text, thumbs), payload, images)
            if (ready && state == SessionState.IDLE) {
                prompt(message)
            } else {
                queue.addLast(message)
                notifyQueue()
            }
        }
    }

    private fun prompt(message: Queued) {
        val c = client ?: return
        val id = sessionId ?: return
        emit(ChatItem.UserText(nextId("user"), message.shown.text, message.shown.images))
        val images = message.images.takeIf { agent?.images == true }.orEmpty()
        val dropped = message.images.size - images.size
        if (dropped > 0) {
            val count = if (dropped == 1) "1 image was" else "$dropped images were"
            emit(ChatItem.Notice(nextId("notice"), "$name does not accept images; $count not sent.", isError = true))
        }
        turnStarted = System.currentTimeMillis()
        val gen = generation
        val future = c.prompt(id, message.text, images)
        turn = future
        beginRunning(Activity.Starting)
        reportActivity()
        future.whenComplete { stopReason, error ->
            synchronized(lock) { if (gen == generation && turn === future) turnEnded(stopReason, error) }
        }
    }

    private fun turnEnded(stopReason: String?, error: Throwable?) {
        val cause = error?.let(::unwrap)
        if (cause is ConnectionLost) return // onClosed follows and reports it
        turn = null
        closeText()
        cancelPermissions()
        val completed = stopReason == END_TURN
        for (id in runningTools.keys) {
            toolCalls[id]?.let { upsertTool(it.copy(status = if (completed) ToolStatus.DONE else ToolStatus.ERROR)) }
        }
        runningTools.clear()
        phase = null
        val duration = System.currentTimeMillis() - turnStarted
        emit(when {
            interruptRequested || stopReason == CANCELLED -> ChatItem.Notice(nextId("notice"), "Interrupted", isError = false)
            cause != null -> ChatItem.TurnResult(nextId("result"), true, cause.message ?: cause.toString(), duration, null)
            completed -> ChatItem.TurnResult(nextId("result"), false, null, duration, null)
            else -> ChatItem.TurnResult(nextId("result"), true, "Stopped: $stopReason", duration, null)
        })
        interruptRequested = false
        sendNext()
        reportActivity()
    }

    private fun sendNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            setState(SessionState.IDLE)
            return
        }
        notifyQueue()
        prompt(next)
    }

    private fun handle(update: AcpUpdate) {
        when (update) {
            is AcpUpdate.AgentText -> {
                val current = streaming?.takeIf { streamingMessage == update.messageId }
                if (current == null) closeText()
                val next = (current ?: ChatItem.AssistantText(nextId("text"), "", true)).let { it.copy(text = it.text + update.text) }
                streaming = next
                streamingMessage = update.messageId
                emit(next)
                phase = Activity.Writing
            }
            AcpUpdate.Thought -> phase = Activity.Thinking
            is AcpUpdate.Tool -> {
                closeText()
                val tool = merged(update)
                val status = when (update.status) {
                    "pending", "in_progress" -> ToolStatus.RUNNING
                    "completed" -> ToolStatus.DONE
                    "failed" -> ToolStatus.ERROR
                    else -> tool.status
                }
                upsertTool(tool.copy(status = status))
                if (status == ToolStatus.RUNNING) runningTools[tool.id] = tool.name else runningTools.remove(tool.id)
                phase = toolPhase()
            }
            is AcpUpdate.Mode -> {
                modeId = update.id
                notifyState()
            }
            is AcpUpdate.Usage -> if (update.size > 0) {
                val percent = (update.used * 100 / update.size).toInt().coerceIn(0, 100)
                listener.onContext(ContextUsage(update.used, update.size, percent, null, emptyList()))
            }
            is AcpUpdate.Other -> log("ACP update not shown: ${update.kind}")
        }
        reportActivity()
    }

    private fun ask(request: PermissionRequest) {
        closeText()
        val key = nextId("perm")
        val tool = merged(request.tool)
        pending[key] = Pending(request, tool.id, live = tool.id in toolCalls)
        upsertTool(tool.copy(
            status = ToolStatus.AWAITING_PERMISSION,
            permissionRequestId = key,
            choices = request.options.map { Choice(it.id, it.name) },
        ))
        settle()
        reportActivity()
    }

    /** The row for [update]'s tool call with the fields it carries applied. */
    private fun merged(update: AcpUpdate.Tool): ChatItem.ToolCall {
        val base = toolCalls[update.id]
            ?: ChatItem.ToolCall(update.id, update.title ?: "tool", EMPTY, null, ToolStatus.RUNNING, null, null, false)
        return base.copy(
            name = update.title ?: base.name,
            input = update.input ?: base.input,
            filePath = update.path ?: base.filePath,
            resultPreview = update.text?.take(MAX_PREVIEW) ?: base.resultPreview,
        )
    }

    override fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
            if (decision !is PermissionDecision.Option) {
                log("An ACP permission is answered with one of the agent's options, not $decision")
                return
            }
            val p = pending[requestId] ?: return
            val option = p.request.options.firstOrNull { it.id == decision.optionId } ?: return
            pending.remove(requestId)
            p.request.select(option.id)
            val status = when {
                option.kind.startsWith("reject") -> ToolStatus.DENIED
                p.live -> ToolStatus.RUNNING
                else -> ToolStatus.DONE
            }
            if (status != ToolStatus.RUNNING) runningTools.remove(p.toolId)
            toolCalls[p.toolId]?.let { upsertTool(it.copy(status = status, permissionRequestId = null)) }
            phase = toolPhase()
            settle()
            reportActivity()
        }
    }

    /** Answers every open permission request `cancelled`, as ACP asks of a client that stops a turn. */
    private fun cancelPermissions() {
        for (p in pending.values) {
            p.request.cancel()
            runningTools.remove(p.toolId)
            toolCalls[p.toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
        }
        pending.clear()
    }

    override fun setMode(id: String) {
        synchronized(lock) {
            val c = client ?: return
            val session = sessionId ?: return
            if (!ready || modes.none { it.id == id }) return
            val gen = generation
            c.setMode(session, id).whenComplete { _, error ->
                synchronized(lock) {
                    if (gen != generation) return@synchronized
                    if (error == null) modeId = id
                    else emit(ChatItem.Notice(nextId("notice"), "Couldn't change the mode: ${messageOf(error)}", isError = true))
                    notifyState()
                }
            }
        }
    }

    override fun unqueue(id: String): QueuedMessage? = synchronized(lock) {
        val i = queue.indexOfFirst { it.shown.id == id }
        if (i < 0) return null
        val removed = queue.removeAt(i).shown
        notifyQueue()
        removed
    }

    override fun sendNow(id: String) {
        synchronized(lock) {
            if (!prompting) return
            val i = queue.indexOfFirst { it.shown.id == id }
            if (i < 0) return
            if (i > 0) {
                queue.addFirst(queue.removeAt(i))
                notifyQueue()
            }
            if (!interruptRequested) stopTurn()
        }
    }

    override fun interrupt() {
        synchronized(lock) {
            if (!prompting) return
            dropQueue()
            if (!interruptRequested) stopTurn()
        }
    }

    /** The turn stays open until the agent answers the prompt (stop reason `cancelled`). */
    private fun stopTurn() {
        interruptRequested = true
        cancelPermissions()
        sessionId?.let { client?.cancel(it) }
        settle()
        reportActivity()
    }

    override fun notice(text: String, isError: Boolean) {
        synchronized(lock) { emit(ChatItem.Notice(nextId("notice"), text, isError)) }
    }

    override fun restart() {
        synchronized(lock) {
            if (state != SessionState.DEAD) return
            start()
        }
    }

    override fun dispose() {
        synchronized(lock) {
            generation++
            val c = client ?: return
            client = null
            val running = turn
            val id = sessionId
            if (running == null || id == null) {
                c.close()
                return
            }
            // Hermes keeps running a turn whose viewer went away: the cancel must reach it before the process ends.
            pending.values.forEach { it.request.cancel() }
            pending.clear()
            c.cancel(id)
            running.whenComplete { _, _ -> c.close() }
            CompletableFuture.delayedExecutor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS).execute(c::close)
        }
    }

    override fun diffFor(toolUseId: String): FileDiff? = null

    private fun closed(exitCode: Int?, detail: String) {
        client = null
        ready = false
        loading = false
        turn = null
        interruptRequested = false
        pending.clear()
        runningTools.clear()
        closeText()
        dropQueue()
        phase = null
        val head = "$name exited (code ${exitCode ?: "unknown"})."
        val tail = detail.trim().takeLast(2000)
        emit(ChatItem.Notice(nextId("notice"), if (tail.isEmpty()) head else "$head\n$tail", isError = true))
        setState(SessionState.DEAD)
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

    private fun closeText() {
        streaming?.let { emit(it.copy(streaming = false)) }
        streaming = null
        streamingMessage = null
    }

    private fun toolPhase(): Activity = runningTools.entries.lastOrNull()?.let { Activity.Running(it.value) } ?: Activity.Thinking

    private fun settle() = setState(when {
        pending.isNotEmpty() -> SessionState.AWAITING_PERMISSION
        prompting -> SessionState.RUNNING
        else -> SessionState.IDLE
    })

    private fun reportActivity() {
        val now = if (!prompting) null else if (pending.isNotEmpty()) Activity.Waiting else phase
        if (now == reported) return
        reported = now
        listener.onActivity(now)
    }

    private fun beginRunning(phase: Activity) {
        this.phase = phase
        setState(SessionState.RUNNING)
    }

    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    private fun notifyState() = listener.onState(state, sessionId, modeId)
    private fun notifyQueue() = listener.onQueue(queue.map { it.shown })

    private companion object {
        const val END_TURN = "end_turn"
        const val CANCELLED = "cancelled"
        const val MAX_PREVIEW = 4000
        const val CLOSE_WAIT_SECONDS = 3L
        val EMPTY = JsonObject(emptyMap())

        fun unwrap(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error
        fun messageOf(error: Throwable?): String = error?.let(::unwrap)?.let { it.message ?: it.toString() } ?: "unknown error"
    }
}
