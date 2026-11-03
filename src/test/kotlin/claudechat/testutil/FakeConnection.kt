package claudechat.testutil

import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.ChatItem
import claudechat.session.ChatListener
import claudechat.session.SessionState
import kotlinx.serialization.json.JsonObject

class FakeConnection : CliConnection {
    val sent = mutableListOf<String>()
    var stopped = false
    private var onLine: ((String) -> Unit)? = null
    private var onExit: ((Int, String) -> Unit)? = null

    override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) {
        this.onLine = onLine
        this.onExit = onExit
    }
    override fun send(line: String) { sent += line }
    override fun stop() { stopped = true }

    fun emit(vararg lines: String) = lines.forEach { onLine!!(it) }
    fun exit(code: Int, stderr: String) = onExit!!(code, stderr)

    fun sentJson(): List<JsonObject> = sent.map { parseJsonObject(it)!! }
    /** request_id of the most recent control_request with the given subtype. */
    fun lastRequestId(subtype: String): String =
        sentJson().last { it.str("type") == "control_request" && it.obj("request")?.str("subtype") == subtype }.str("request_id")!!
    fun userMessages(): List<String> =
        sentJson().filter { it.str("type") == "user" }.map { it.obj("message")!!.str("content")!! }
}

class RecordingListener : ChatListener {
    val items = mutableListOf<ChatItem>()
    val states = mutableListOf<Triple<SessionState, String?, PermissionMode?>>()
    override fun onItem(item: ChatItem) { items += item }
    override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) { states += Triple(state, sessionId, mode) }

    /** Latest version of every item, in first-seen order. */
    fun current(): List<ChatItem> = items.associateBy { it.id }.values.toList()
    inline fun <reified T : ChatItem> latest(): T = items.filterIsInstance<T>().last()
    fun lastState(): SessionState = states.last().first
}
