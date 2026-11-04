package claudechat.session

import claudechat.image.Image

/** One chat's conversation with its agent: [ChatSession] for Claude, `AcpSession` for an ACP agent. Thread-safe. */
interface AgentSession {
    val state: SessionState
    val sessionId: String?
    val modeId: String?
    /** The folder the agent works in, as far as known now. */
    val cwd: String

    fun start()

    /**
     * Shows [text] (with [thumbs] above it) in the chat; sends "[prefix]\n\n[text]" and [images] to the agent. While a
     * turn is running the message is queued instead (see [ChatListener.onQueue]) and enters the chat when it goes out.
     */
    fun send(text: String, prefix: String? = null, images: List<Image> = emptyList(), thumbs: List<String> = emptyList())

    /** Takes queued message [id] out of the queue without sending it; null when it is not queued (any more). */
    fun unqueue(id: String): QueuedMessage?

    /** Stops the running turn and sends queued message [id] next; the rest of the queue follows it. */
    fun sendNow(id: String)

    /** Shows a notice in the chat (e.g. an image that could not be attached). Any thread. */
    fun notice(text: String, isError: Boolean = true)

    fun respond(requestId: String, decision: PermissionDecision)

    /** Ignored when the agent offers no mode [id]. */
    fun setMode(id: String)

    /** Stops the running turn; queued messages are dropped (see [ChatListener.onDropped]). */
    fun interrupt()

    /** Starts a dead session again, continuing the same conversation where the agent can. */
    fun restart()

    fun dispose()

    fun diffFor(toolUseId: String): FileDiff?
}
