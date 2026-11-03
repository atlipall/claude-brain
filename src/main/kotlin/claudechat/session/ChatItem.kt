package claudechat.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolStatus { RUNNING, AWAITING_PERMISSION, DONE, ERROR, DENIED }

/** One entry in the chat log. Items are upserted by [id]. */
sealed interface ChatItem {
    val id: String

    /** [images]: thumbnails of the attached images as `data:` URLs ("" for one that has no thumbnail). */
    data class UserText(override val id: String, val text: String, val images: List<String> = emptyList()) : ChatItem
    data class AssistantText(override val id: String, val text: String, val streaming: Boolean) : ChatItem
    data class ToolCall(
        override val id: String,
        val name: String,
        val input: JsonObject,
        val filePath: String?,
        val status: ToolStatus,
        val permissionRequestId: String?,
        val resultPreview: String?,
        val hasDiff: Boolean,
    ) : ChatItem
    data class TurnResult(override val id: String, val isError: Boolean, val text: String?, val durationMs: Long?, val costUsd: Double?) : ChatItem
    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatItem
}

fun ChatItem.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    when (val item = this@toJson) {
        is ChatItem.UserText -> {
            put("kind", "user")
            put("text", item.text)
            if (item.images.isNotEmpty()) put("images", JsonArray(item.images.map(::JsonPrimitive)))
        }
        is ChatItem.AssistantText -> { put("kind", "assistant"); put("text", item.text); put("streaming", item.streaming) }
        is ChatItem.ToolCall -> {
            put("kind", "tool")
            put("name", item.name)
            put("input", item.input)
            put("filePath", item.filePath)
            put("status", item.status.name.lowercase())
            put("permissionRequestId", item.permissionRequestId)
            put("resultPreview", item.resultPreview)
            put("hasDiff", item.hasDiff)
        }
        is ChatItem.TurnResult -> {
            put("kind", "result")
            put("isError", item.isError)
            put("text", item.text)
            put("durationMs", item.durationMs)
            put("costUsd", item.costUsd)
        }
        is ChatItem.Notice -> { put("kind", "notice"); put("text", item.text); put("isError", item.isError) }
    }
}
