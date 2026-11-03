package claudechat.ui

import claudechat.cli.PermissionMode
import claudechat.cli.arr
import claudechat.cli.long
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.ide.FileLinker
import claudechat.session.ChatItem
import claudechat.session.PermissionDecision
import claudechat.session.SessionManager
import claudechat.session.SessionState
import claudechat.session.SessionSummary
import claudechat.session.SessionView
import claudechat.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Translates web-view messages into SessionManager/FileLinker calls and chat updates into web-view messages. */
class ChatBridge(private val project: Project, private val post: (String) -> Unit) : SessionView {
    private val manager get() = SessionManager.getInstance(project)
    private val linker get() = FileLinker.getInstance(project)

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> { post(Theme.message()); manager.attach(this) }
            "send" -> msg.str("text")?.takeIf { it.isNotBlank() }?.let(manager::send)
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                manager.current?.respond(requestId, decision)
            }
            "setMode" -> PermissionMode.fromCli(msg.str("mode"))?.let { manager.current?.setPermissionMode(it) }
            "interrupt" -> manager.current?.interrupt()
            "newChat" -> manager.newChat()
            "restart" -> manager.current?.restart()
            "listSessions" -> post(sessionsMessage(manager.listSessions()))
            "resume" -> msg.str("sessionId")?.let(manager::resume)
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "openDiff" -> msg.str("toolUseId")?.let { id -> manager.current?.diffFor(id)?.let(linker::showDiff) }
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
        }
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

    private fun sessionsMessage(sessions: List<SessionSummary>) = buildJsonObject {
        put("type", "sessions")
        put("sessions", JsonArray(sessions.map { s ->
            buildJsonObject { put("id", s.id); put("title", s.title); put("modified", s.modifiedMillis) }
        }))
    }.toString()

    private fun resolvedMessage(reqId: Long, candidates: List<String>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for (candidate in candidates) {
                val resolved = linker.resolve(candidate) ?: continue
                put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
            }
        }
    }.toString()
}
