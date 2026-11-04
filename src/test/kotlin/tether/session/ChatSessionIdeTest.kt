package tether.session

import tether.cli.McpTool
import tether.cli.ToolOutput
import tether.cli.arr
import tether.cli.obj
import tether.cli.str
import tether.testutil.FakeConnection
import tether.testutil.Fx
import tether.testutil.RecordingListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a session lends Claude the IDE's tools and edit checks ([IdeHost]). */
class ChatSessionIdeTest {
    private val connections = mutableListOf<FakeConnection>()
    private val conn get() = connections.last()
    private val listener = RecordingListener()
    private val deferred = mutableListOf<Runnable>()
    private val edits = mutableListOf<String>()

    private val ping = object : McpTool {
        override val name = "ping"
        override val description = "Pings"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override val readOnly = true
        override fun call(args: JsonObject) = ToolOutput("pong")
    }
    private val poke = object : McpTool {
        override val name = "poke"
        override val description = "Changes things"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun call(args: JsonObject) = ToolOutput("poked")
    }

    private fun host(tools: List<McpTool> = listOf(ping), checks: Boolean = true, note: String? = "1 error") = object : IdeHost {
        override val tools = tools
        override val checksEdits = checks
        override fun afterEdit(filePath: String): String? { edits += filePath; return note }
    }

    private fun session(ide: IdeHost?) = ChatSession(
        connect = { FakeConnection().also { connections += it } },
        listener = listener,
        cwd = "/proj",
        ide = ide,
        background = { deferred += it },
    ).also { it.start() }

    private fun initRequest() = conn.sentJson().first { it.obj("request")?.str("subtype") == "initialize" }.obj("request")!!
    private fun runBackground() { deferred.toList().forEach { it.run() }; deferred.clear() }
    private fun responseTo(requestId: String) =
        conn.sentJson().last { it.str("type") == "control_response" && it.obj("response")?.str("request_id") == requestId }.obj("response")!!

    @Test fun withoutAHostInitializeOffersNothing() {
        session(null)
        val req = initRequest()
        assertEquals(kotlinx.serialization.json.JsonNull, req["hooks"])
        assertNull(req["sdkMcpServers"])
    }

    @Test fun initializeOffersTheServerAndTheEditHook() {
        session(host())
        val req = initRequest()
        assertEquals(listOf("tether"), req.arr("sdkMcpServers")!!.map { (it as JsonPrimitive).content })
        val hook = req.obj("hooks")!!.arr("PostToolUse")!!.single().jsonObject
        assertEquals("Edit|Write|MultiEdit|NotebookEdit", hook.str("matcher"))
    }

    @Test fun noToolsAndNoChecksOfferNothing() {
        session(host(tools = emptyList(), checks = false))
        val req = initRequest()
        assertNull(req["sdkMcpServers"])
        assertEquals(kotlinx.serialization.json.JsonNull, req["hooks"])
    }

    @Test fun aToolCallRunsInTheBackgroundAndAnswersTheRequest() {
        session(host())
        conn.emit(Fx.mcpMessage("c1", "tether", """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ping","arguments":{}}}"""))
        assertTrue("nothing answered before the tool ran", conn.sentJson().none { it.obj("response")?.str("request_id") == "c1" })
        runBackground()
        val reply = responseTo("c1").obj("response")!!.obj("mcp_response")!!
        assertEquals(5L, reply["id"].toString().toLong())
        assertEquals("pong", reply.obj("result")!!.arr("content")!!.single().jsonObject.str("text"))
    }

    @Test fun notificationsAreAcknowledgedAtOnce() {
        session(host())
        conn.emit(Fx.mcpMessage("c1", "tether", """{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertTrue(deferred.isEmpty())
        assertEquals("success", responseTo("c1").str("subtype"))
    }

    @Test fun anotherServersMessageIsRefused() {
        session(host())
        conn.emit(Fx.mcpMessage("c1", "other", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
        assertEquals("error", responseTo("c1").str("subtype"))
    }

    @Test fun theIdesToolsNeedNoPermission() {
        session(host())
        conn.emit(Fx.canUseTool("p1", "t1", "mcp__tether__ping", "{}"))
        assertEquals("allow", responseTo("p1").obj("response")!!.str("behavior"))
        assertEquals(SessionState.IDLE, listener.lastState())
    }

    @Test fun theIdesWritingToolsAsk() {
        session(host(tools = listOf(ping, poke)))
        conn.emit(Fx.canUseTool("p1", "t1", "mcp__tether__poke", "{}"))
        assertEquals(SessionState.AWAITING_PERMISSION, listener.lastState())
    }

    @Test fun withoutToolsAToolOfThatNameStillAsks() {
        session(host(tools = emptyList()))
        conn.emit(Fx.canUseTool("p1", "t1", "mcp__tether__ping", "{}"))
        assertEquals(SessionState.AWAITING_PERMISSION, listener.lastState())
    }

    @Test fun anEditIsCheckedAndTheNoteReturnedAsContext() {
        session(host())
        conn.emit(Fx.hookCallback("h1", "after-edit", "Edit", "/proj/a.ts"))
        runBackground()
        assertEquals(listOf("/proj/a.ts"), edits)
        val output = responseTo("h1").obj("response")!!.obj("hookSpecificOutput")!!
        assertEquals("PostToolUse", output.str("hookEventName"))
        assertEquals("1 error", output.str("additionalContext"))
    }

    @Test fun aCleanEditGetsAnEmptyAnswer() {
        session(host(note = null))
        conn.emit(Fx.hookCallback("h1", "after-edit", "Write", "/proj/a.ts"))
        runBackground()
        assertEquals(JsonObject(emptyMap()), responseTo("h1").obj("response"))
    }

    @Test fun aFailingCheckStillAnswersTheHook() {
        val failing = object : IdeHost {
            override val tools = emptyList<McpTool>()
            override val checksEdits = true
            override fun afterEdit(filePath: String): String = throw IllegalStateException("boom")
        }
        session(failing)
        conn.emit(Fx.hookCallback("h1", "after-edit", "Edit", "/proj/a.ts"))
        runBackground()
        assertEquals(JsonObject(emptyMap()), responseTo("h1").obj("response"))
    }

    @Test fun anUnknownCallbackIsAnsweredEmpty() {
        session(host())
        conn.emit(Fx.hookCallback("h1", "mystery", "Edit", "/proj/a.ts"))
        assertTrue(edits.isEmpty())
        assertEquals(JsonObject(emptyMap()), responseTo("h1").obj("response"))
    }
}
