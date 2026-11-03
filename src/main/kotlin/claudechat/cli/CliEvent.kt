package claudechat.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A base64 image content block as the CLI sent it: [mediaType] is what the CLI claims, [data] is not yet checked. */
data class ImageBlock(val mediaType: String?, val data: String)

sealed interface CliEvent {
    data class SystemInit(val sessionId: String, val permissionMode: String?, val cwd: String?) : CliEvent
    data class TextDelta(val messageId: String, val index: Int, val text: String) : CliEvent
    data class BlockStop(val messageId: String, val index: Int) : CliEvent
    data class AssistantText(val messageId: String, val text: String) : CliEvent
    data class ToolUse(val toolUseId: String, val name: String, val input: JsonObject) : CliEvent
    data class ToolResult(
        val toolUseId: String,
        val isError: Boolean,
        val content: String,
        val filePath: String?,
        val originalFile: String?,
        val hasOriginalFile: Boolean,
        /** Image blocks inside the result's content (e.g. Read on a PNG), unvalidated. */
        val images: List<ImageBlock> = emptyList(),
    ) : CliEvent
    data class UserText(val text: String) : CliEvent
    data class PermissionRequest(val requestId: String, val toolName: String, val toolUseId: String?, val input: JsonObject) : CliEvent
    data class ControlResponse(val requestId: String, val isSuccess: Boolean, val response: JsonObject?, val error: String?) : CliEvent
    data class Result(
        val isError: Boolean,
        val subtype: String,
        val text: String?,
        val durationMs: Long?,
        val costUsd: Double?,
        val errors: List<String>,
    ) : CliEvent
    data class Ignored(val type: String) : CliEvent
    data class Malformed(val line: String) : CliEvent
}

/** Turns one stdout line of `claude --output-format stream-json` into zero or more events. */
object CliParser {
    private val EMPTY = JsonObject(emptyMap())

    fun parse(line: String): List<CliEvent> {
        val obj = parseJsonObject(line) ?: return listOf(CliEvent.Malformed(line))
        return when (val type = obj.str("type")) {
            "system" ->
                if (obj.str("subtype") == "init") listOf(CliEvent.SystemInit(obj.str("session_id") ?: "", obj.str("permissionMode"), obj.str("cwd")))
                else listOf(CliEvent.Ignored("system/${obj.str("subtype")}"))
            "stream_event" -> listOf(streamEvent(obj))
            "assistant" -> assistant(obj)
            "user" -> user(obj)
            "control_request" -> listOf(controlRequest(obj))
            "control_response" -> listOf(controlResponse(obj))
            "result" -> listOf(result(obj))
            else -> listOf(CliEvent.Ignored(type ?: "?"))
        }
    }

    private fun streamEvent(obj: JsonObject): CliEvent {
        val messageId = obj.str("api_message_id") ?: ""
        val event = obj.obj("event") ?: return CliEvent.Ignored("stream_event/?")
        val index = event.long("index")?.toInt() ?: 0
        return when (val type = event.str("type")) {
            "content_block_delta" -> {
                val delta = event.obj("delta")
                if (delta?.str("type") == "text_delta") CliEvent.TextDelta(messageId, index, delta.str("text") ?: "")
                else CliEvent.Ignored("stream_event/content_block_delta/${delta?.str("type")}")
            }
            "content_block_stop" -> CliEvent.BlockStop(messageId, index)
            else -> CliEvent.Ignored("stream_event/$type")
        }
    }

    private fun assistant(obj: JsonObject): List<CliEvent> {
        val message = obj.obj("message") ?: return emptyList()
        val messageId = message.str("id") ?: ""
        return message.arr("content").orEmpty().mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> CliEvent.AssistantText(messageId, b.str("text") ?: "")
                "tool_use" -> CliEvent.ToolUse(b.str("id") ?: "", b.str("name") ?: "", b.obj("input") ?: EMPTY)
                else -> null
            }
        }
    }

    private fun user(obj: JsonObject): List<CliEvent> {
        val content = obj.obj("message")?.get("content") ?: return emptyList()
        if (content is JsonPrimitive) return listOf(CliEvent.UserText(content.content))
        val blocks = (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val toolUseResult = obj["tool_use_result"] as? JsonObject
        val singleResult = blocks.count { it.str("type") == "tool_result" } == 1
        return blocks.mapNotNull { b ->
            when (b.str("type")) {
                "tool_result" -> {
                    val extra = if (singleResult) toolUseResult else null
                    val original = extra?.get("originalFile")
                    CliEvent.ToolResult(
                        toolUseId = b.str("tool_use_id") ?: "",
                        isError = b.bool("is_error") == true,
                        content = textOf(b["content"]),
                        filePath = extra?.str("filePath"),
                        originalFile = if (original == null || original is JsonNull) null else (original as? JsonPrimitive)?.content,
                        hasOriginalFile = extra?.containsKey("originalFile") == true,
                        images = imagesOf(b["content"]),
                    )
                }
                "text" -> CliEvent.UserText(b.str("text") ?: "")
                else -> null
            }
        }
    }

    private fun textOf(content: Any?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }

    /** `{"type":"image","source":{"type":"base64","media_type":…,"data":…}}` blocks of a tool result's content. */
    private fun imagesOf(content: Any?): List<ImageBlock> =
        (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it.str("type") == "image" }
            .mapNotNull { it.obj("source") }
            .filter { it.str("type") == "base64" }
            .mapNotNull { source -> source.str("data")?.let { ImageBlock(source.str("media_type"), it) } }

    private fun controlRequest(obj: JsonObject): CliEvent {
        val request = obj.obj("request") ?: return CliEvent.Ignored("control_request/?")
        return when (val subtype = request.str("subtype")) {
            "can_use_tool" -> CliEvent.PermissionRequest(
                requestId = obj.str("request_id") ?: "",
                toolName = request.str("tool_name") ?: "",
                toolUseId = request.str("tool_use_id"),
                input = request.obj("input") ?: EMPTY,
            )
            else -> CliEvent.Ignored("control_request/$subtype")
        }
    }

    private fun controlResponse(obj: JsonObject): CliEvent {
        val response = obj.obj("response") ?: return CliEvent.Ignored("control_response/?")
        return CliEvent.ControlResponse(
            requestId = response.str("request_id") ?: "",
            isSuccess = response.str("subtype") == "success",
            response = response.obj("response"),
            error = response.str("error"),
        )
    }

    private fun result(obj: JsonObject) = CliEvent.Result(
        isError = obj.bool("is_error") == true,
        subtype = obj.str("subtype") ?: "",
        text = obj.str("result"),
        durationMs = obj.long("duration_ms"),
        costUsd = obj.double("total_cost_usd"),
        errors = obj.arr("errors").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
    )
}
