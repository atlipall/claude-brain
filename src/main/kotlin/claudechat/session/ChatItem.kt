package claudechat.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolStatus { RUNNING, AWAITING_PERMISSION, DONE, ERROR, DENIED }

enum class AgentState { RUNNING, DONE, FAILED }

/** What the CLI's task events told about the subagent an Agent call started. */
data class AgentInfo(
    val subagentType: String?,
    val background: Boolean,
    val prompt: String?,
    val state: AgentState,
    val toolUses: Int? = null,
    val durationMs: Long? = null,
    /** The tool the subagent used last, while it runs. */
    val lastTool: String? = null,
    val report: String? = null,
)

/** An option an agent offers: a permission answer or a mode. */
data class Choice(val id: String, val label: String)

/** One entry in the chat log. Items are upserted by [id]. A `parentId` is the Agent call whose subagent produced the item. */
sealed interface ChatItem {
    val id: String

    /**
     * [images]: thumbnails of the attached images as `data:` URLs ("" for one that has no thumbnail). [revertable]:
     * the IDE marked the files' state when this message went out, so they can be reverted to it.
     */
    data class UserText(override val id: String, val text: String, val images: List<String> = emptyList(), val revertable: Boolean = false) : ChatItem
    data class AssistantText(override val id: String, val text: String, val streaming: Boolean, val parentId: String? = null) : ChatItem
    data class ToolCall(
        override val id: String,
        val name: String,
        val input: JsonObject,
        val filePath: String?,
        val status: ToolStatus,
        val permissionRequestId: String?,
        val resultPreview: String?,
        val hasDiff: Boolean,
        /** Images in the tool's result as `data:` URLs of display copies ("" for one that can't be shown). */
        val images: List<String> = emptyList(),
        /** Set for an AskUserQuestion call that is waiting for (or has received) the user's answers. */
        val question: QuestionCard? = null,
        val parentId: String? = null,
        /** Set for an Agent/Task call once the CLI reports on its subagent. */
        val agent: AgentInfo? = null,
        /** The answers an ACP agent offers for its permission request; empty shows Claude's Allow / Deny buttons. */
        val choices: List<Choice> = emptyList(),
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
            if (item.revertable) put("revertable", true)
        }
        is ChatItem.AssistantText -> {
            put("kind", "assistant")
            put("text", item.text)
            put("streaming", item.streaming)
            item.parentId?.let { put("parentId", it) }
        }
        is ChatItem.ToolCall -> {
            put("kind", "tool")
            put("name", item.name)
            put("input", item.input)
            put("filePath", item.filePath)
            put("status", item.status.name.lowercase())
            put("permissionRequestId", item.permissionRequestId)
            put("resultPreview", item.resultPreview)
            put("hasDiff", item.hasDiff)
            if (item.images.isNotEmpty()) put("images", JsonArray(item.images.map(::JsonPrimitive)))
            item.question?.let { put("question", it.toJson()) }
            item.parentId?.let { put("parentId", it) }
            item.agent?.let { a ->
                put("agent", buildJsonObject {
                    put("subagentType", a.subagentType)
                    put("background", a.background)
                    put("prompt", a.prompt)
                    put("state", a.state.name.lowercase())
                    put("toolUses", a.toolUses)
                    put("durationMs", a.durationMs)
                    put("lastTool", a.lastTool)
                    put("report", a.report)
                })
            }
            if (item.choices.isNotEmpty()) put("choices", JsonArray(item.choices.map { it.toJson() }))
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

fun Choice.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("label", label)
}
