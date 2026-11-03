package claudechat.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerTest {
    private val calls = mutableListOf<JsonObject>()
    private val echo = object : McpTool {
        override val name = "echo"
        override val description = "Echoes"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun call(args: JsonObject): ToolOutput { calls += args; return ToolOutput("said ${args.str("word")}") }
    }
    private val broken = object : McpTool {
        override val name = "broken"
        override val description = "Throws"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override fun call(args: JsonObject): ToolOutput = throw IllegalStateException("boom")
    }
    private val server = McpServer("tether", listOf(echo, broken))

    private fun reply(json: String) = server.reply(parseJsonObject(json)!!)

    @Test fun initializeEchoesTheProtocolVersionAndOffersTools() {
        val r = reply("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")
        assertEquals("2.0", r.str("jsonrpc"))
        assertEquals(0L, r.long("id"))
        val result = r.obj("result")!!
        assertEquals("2025-11-25", result.str("protocolVersion"))
        assertTrue(result.obj("capabilities")!!.containsKey("tools"))
        assertEquals("tether", result.obj("serverInfo")!!.str("name"))
    }

    @Test fun toolsListDescribesEveryTool() {
        val tools = reply("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").obj("result")!!.arr("tools")!!.map { it.jsonObject }
        assertEquals(listOf("echo", "broken"), tools.map { it.str("name") })
        assertEquals("Echoes", tools[0].str("description"))
        assertEquals("object", tools[0].obj("inputSchema")!!.str("type"))
    }

    @Test fun toolsCallRunsTheToolWithItsArguments() {
        val r = reply("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"word":"hi"}}}""")
        assertEquals(2L, r.long("id"))
        val result = r.obj("result")!!
        assertEquals("said hi", result.arr("content")!!.single().jsonObject.str("text"))
        assertNull(result["isError"])
        assertEquals("hi", calls.single().str("word"))
    }

    @Test fun aFailingToolIsAnErrorResultNotACrash() {
        val result = reply("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"broken","arguments":{}}}""").obj("result")!!
        assertEquals(true, result.bool("isError"))
        assertTrue(result.arr("content")!!.single().jsonObject.str("text")!!.contains("boom"))
    }

    @Test fun anUnknownToolIsAnErrorResult() {
        val result = reply("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"nope"}}""").obj("result")!!
        assertEquals(true, result.bool("isError"))
    }

    @Test fun anUnknownMethodIsAJsonRpcError() {
        val r = reply("""{"jsonrpc":"2.0","id":"x","method":"resources/list"}""")
        assertEquals("x", r.str("id"))
        assertEquals(-32601L, r.obj("error")!!.long("code"))
    }

    @Test fun notificationsAreNotRequests() {
        assertFalse(server.isRequest(parseJsonObject("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")!!))
        assertFalse(server.isRequest(parseJsonObject("""{"jsonrpc":"2.0","id":null,"method":"x"}""")!!))
        assertTrue(server.isRequest(parseJsonObject("""{"jsonrpc":"2.0","id":0,"method":"initialize"}""")!!))
    }
}
