package tether.cli

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject

/** What a tool call hands back to Claude. */
data class ToolOutput(val text: String, val isError: Boolean = false)

/** A tool the plugin lends Claude. [call] runs on a pooled thread and may block (but should stay well under a minute). */
interface McpTool {
    /** Claude sees it as `mcp__<server>__<name>`. */
    val name: String
    val description: String
    /** JSON Schema of the arguments. */
    val inputSchema: JsonObject
    /** True when the tool changes nothing: it is then allowed without asking. */
    val readOnly: Boolean get() = false
    fun call(args: JsonObject): ToolOutput
}

/**
 * The JSON-RPC side of an in-process ("sdk") MCP server: the CLI forwards Claude's MCP traffic to it in `mcp_message`
 * control requests (see [CliEvent.McpMessage]) and expects the JSON-RPC reply in the control response.
 */
class McpServer(private val name: String, private val tools: List<McpTool>) {
    /** True for a request (it has an id); a notification only needs an empty acknowledgement. */
    fun isRequest(message: JsonObject): Boolean = message["id"].let { it != null && it !is kotlinx.serialization.json.JsonNull }

    /** The JSON-RPC reply to request [message]. May block while a tool runs. */
    fun reply(message: JsonObject): JsonObject {
        val id = message["id"] ?: JsonPrimitive(0)
        val params = message.obj("params")
        return when (val method = message.str("method")) {
            "initialize" -> result(id) {
                put("protocolVersion", params?.str("protocolVersion") ?: PROTOCOL_VERSION)
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", name); put("version", "1") }
            }
            "tools/list" -> result(id) {
                putJsonArray("tools") {
                    for (tool in tools) addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        putJsonObject("annotations") { put("readOnlyHint", tool.readOnly) }
                    }
                }
            }
            "tools/call" -> {
                val tool = tools.firstOrNull { it.name == params?.str("name") }
                val output = when {
                    tool == null -> ToolOutput("Unknown tool: ${params?.str("name")}", isError = true)
                    else -> try {
                        tool.call(params?.obj("arguments") ?: JsonObject(emptyMap()))
                    } catch (e: Throwable) {
                        if (e is InterruptedException) Thread.currentThread().interrupt()
                        ToolOutput("The IDE failed to run ${tool.name}: ${e.message ?: e.javaClass.simpleName}", isError = true)
                    }
                }
                result(id) {
                    putJsonArray("content") { addJsonObject { put("type", "text"); put("text", output.text) } }
                    if (output.isError) put("isError", true)
                }
            }
            "ping" -> result(id) {}
            else -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") { put("code", -32601); put("message", "Method not found: $method") }
            }
        }
    }

    private fun result(id: JsonElement, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("result", body)
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        /** What the CLI expects back for a notification (the Agent SDK sends the same). */
        val ACK: JsonObject = buildJsonObject { put("jsonrpc", "2.0"); putJsonObject("result") {}; put("id", 0) }
    }
}
