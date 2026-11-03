package claudechat.ui

import claudechat.cli.PermissionMode
import claudechat.cli.arr
import claudechat.cli.long
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.ide.FileLinker
import claudechat.session.Chat
import claudechat.session.ChatItem
import claudechat.session.PermissionDecision
import claudechat.session.PromptHistory
import claudechat.session.SessionState
import claudechat.session.SessionView
import claudechat.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Connects one chat page to one [Chat]: web-view messages in, chat updates out. */
class ChatBridge(
    private val project: Project,
    private val chat: Chat,
    private val onNewChat: () -> Unit,
    private val post: (String) -> Unit,
) : SessionView {
    private val linker get() = FileLinker.getInstance(project)
    private val session get() = chat.session
    private val history get() = PromptHistory.getInstance(project)
    @Volatile private var unsubscribe: (() -> Unit)? = null

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> {
                post(Theme.message())
                post(historyMessage(history.entries()))
                if (unsubscribe == null) unsubscribe = history.subscribe { post(historyMessage(it)) }
                chat.attach(this)
            }
            "send" -> msg.str("text")?.takeIf { it.isNotBlank() }?.let { text ->
                if (session.state != SessionState.DEAD) history.add(text) // Chat.send ignores a dead chat
                chat.send(text)
            }
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                session.respond(requestId, decision)
            }
            "setMode" -> PermissionMode.fromCli(msg.str("mode"))?.let(session::setPermissionMode)
            "interrupt" -> session.interrupt()
            "newChat" -> onNewChat()
            "restart" -> session.restart()
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "openDiff" -> msg.str("toolUseId")?.let { id -> session.diffFor(id)?.let(linker::showDiff) }
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
        }
    }

    /** Stops history updates. Called when the page goes away. */
    fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override fun reset(items: List<ChatItem>) = post(buildJsonObject {
        put("type", "reset")
        put("items", JsonArray(items.map { it.toJson() }))
    }.toString())

    override fun item(item: ChatItem) = post(buildJsonObject {
        put("type", "item")
        put("item", item.toJson())
    }.toString())

    override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)
        put("sessionId", sessionId)
        put("mode", mode?.cli)
    }.toString())

    override fun insert(text: String) = post(buildJsonObject {
        put("type", "insert")
        put("text", text)
    }.toString())

    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())

    private fun historyMessage(entries: List<String>) = buildJsonObject {
        put("type", "history")
        put("entries", JsonArray(entries.map(::JsonPrimitive)))
    }.toString()

    private fun resolvedMessage(reqId: Long, candidates: List<String>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for (candidate in candidates) {
                val resolved = linker.resolve(candidate, chat.cwd) ?: continue
                put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
            }
        }
    }.toString()
}
