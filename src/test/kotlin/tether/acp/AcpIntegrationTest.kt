package tether.acp

import tether.cli.long
import tether.cli.obj
import tether.cli.str
import tether.session.ChatItem
import tether.session.PermissionDecision
import tether.session.SessionState
import tether.session.ToolStatus
import tether.testutil.FakeConnection
import tether.testutil.RecordingListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AcpIntegrationTest {
    private val connections = mutableListOf<FakeConnection>()
    private val conn get() = connections.last()
    private val listener = RecordingListener()

    private fun idOf(method: String): Long = conn.sentJson().last { it.str("method") == method }.long("id")!!
    private fun reply(method: String, result: String) = conn.emit("""{"jsonrpc":"2.0","id":${idOf(method)},"result":$result}""")
    private fun update(json: String) = conn.emit("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$json}}""")

    private fun started(): AcpSession {
        val s = AcpSession({ JsonAcpClient(FakeConnection().also { connections += it }) { it.run() } }, listener, "/proj", "hermes")
        s.start()
        reply("initialize", """{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"promptCapabilities":{"image":true}}}""")
        reply("session/new", """{"sessionId":"s1","modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Default"},{"id":"accept_edits","name":"Accept edits"}]}}""")
        return s
    }

    @Test fun aPromptWithAPermissionRunsToTheEndOfTheTurn() {
        val s = started()

        s.send("list files")
        update("""{"sessionUpdate":"agent_message_chunk","messageId":"m1","content":{"type":"text","text":"Let me look."}}""")
        conn.emit("""{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"perm-check-1","title":"Run ls","kind":"execute","rawInput":{"command":"ls"}},"options":[{"optionId":"allow_once","name":"Allow once","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}""")
        s.respond(listener.latest<ChatItem.ToolCall>().permissionRequestId!!, PermissionDecision.Option("allow_once"))
        update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"ls","status":"in_progress"}""")
        update("""{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed","content":[{"type":"content","content":{"type":"text","text":"a.txt"}}]}""")
        update("""{"sessionUpdate":"agent_message_chunk","messageId":"m2","content":{"type":"text","text":"One file."}}""")
        reply("session/prompt", """{"stopReason":"end_turn"}""")

        assertEquals("""{"outcome":"selected","optionId":"allow_once"}""", conn.sentJson().single { it.long("id") == 0L && it.str("method") == null }.obj("result")!!.obj("outcome").toString())
        val items = listener.current()
        assertEquals(
            listOf("user", "text:Let me look.", "tool:Run ls:DONE", "tool:ls:DONE", "text:One file.", "result"),
            items.map {
                when (it) {
                    is ChatItem.UserText -> "user"
                    is ChatItem.AssistantText -> "text:${it.text}"
                    is ChatItem.ToolCall -> "tool:${it.name}:${it.status}"
                    is ChatItem.TurnResult -> "result"
                    is ChatItem.Notice -> "notice:${it.text}"
                }
            },
        )
        assertFalse(items.filterIsInstance<ChatItem.AssistantText>().any { it.streaming })
        assertEquals(SessionState.IDLE, s.state)
        assertEquals(ToolStatus.DONE, (items[3] as ChatItem.ToolCall).status)
    }

    @Test fun restoredSessionDisplaysUserAndAssistantReplay() {
        val s = AcpSession({ JsonAcpClient(FakeConnection().also { connections += it }) { it.run() } }, listener, "/proj", "hermes", resumeId = "s1")
        s.start()
        reply("initialize", """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}""")

        update("""{"sessionUpdate":"user_message_chunk","messageId":"u1","content":{"type":"text","text":"prior "}}""")
        update("""{"sessionUpdate":"user_message_chunk","messageId":"u1","content":{"type":"text","text":"question"}}""")
        update("""{"sessionUpdate":"agent_message_chunk","messageId":"a1","content":{"type":"text","text":"prior answer"}}""")
        update("""{"sessionUpdate":"user_message_chunk","messageId":"u2","content":{"type":"text","text":"next question"}}""")
        update("""{"sessionUpdate":"agent_message_chunk","messageId":"a2","content":{"type":"text","text":"next answer"}}""")
        reply("session/load", """{"modes":{"currentModeId":"dont_ask","availableModes":[{"id":"dont_ask","name":"Don't ask"}]}}""")

        assertEquals("s1", conn.sentJson().single { it.str("method") == "session/load" }.obj("params")!!.str("sessionId"))
        assertFalse(conn.sentJson().any { it.str("method") == "session/new" })
        assertEquals(listOf("prior question", "next question"), listener.current().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(listOf("prior answer", "next answer"), listener.current().filterIsInstance<ChatItem.AssistantText>().map { it.text })
        assertFalse(listener.current().filterIsInstance<ChatItem.AssistantText>().any { it.streaming })
        assertEquals("dont_ask", s.modeId)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun stopSendsCancelAndTheTurnEndsWithTheAgentsAnswer() {
        val s = started()
        s.send("long task")

        s.interrupt()
        reply("session/prompt", """{"stopReason":"cancelled"}""")

        assertEquals("s1", conn.sentJson().single { it.str("method") == "session/cancel" }.obj("params")!!.str("sessionId"))
        assertEquals("Interrupted", listener.latest<ChatItem.Notice>().text)
        assertEquals(SessionState.IDLE, s.state)
    }
}
