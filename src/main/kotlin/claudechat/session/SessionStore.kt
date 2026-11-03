package claudechat.session

import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long)

/** Reads Claude Code's on-disk session transcripts (~/.claude/projects/<encoded cwd>/<id>.jsonl). */
class SessionStore(private val claudeHome: File) {
    fun projectDir(cwd: String): File = File(File(claudeHome, "projects"), cwd.replace(NON_ALNUM, "-"))

    fun list(cwd: String, limit: Int = 30): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> firstUserText(f)?.let { SessionSummary(f.nameWithoutExtension, oneLine(it), f.lastModified()) } }
            .take(limit)
            .toList()

    fun transcript(cwd: String, sessionId: String): List<ChatItem> {
        val file = File(projectDir(cwd), "$sessionId.jsonl")
        if (!file.isFile) return emptyList()
        val items = mutableListOf<ChatItem>()
        file.useLines { lines ->
            for (line in lines) {
                val obj = parseJsonObject(line) ?: continue
                when (obj.str("type")) {
                    "user" -> userText(obj)?.let { items += ChatItem.UserText("h-${items.size}", it) }
                    "assistant" -> assistantText(obj)?.let { items += ChatItem.AssistantText("h-${items.size}", it, false) }
                }
            }
        }
        return items
    }

    private fun firstUserText(file: File): String? = file.useLines { lines ->
        lines.mapNotNull { parseJsonObject(it) }.filter { it.str("type") == "user" }.mapNotNull { userText(it) }.firstOrNull()
    }

    private fun userText(obj: JsonObject): String? {
        if (obj.bool("isMeta") == true || obj.bool("isSidechain") == true) return null
        val text = textOf(obj.obj("message")?.get("content")) ?: return null
        if (text.startsWith("<") || text.startsWith("[Request interrupted")) return null
        return stripIdeNote(text)
    }

    private fun assistantText(obj: JsonObject): String? {
        if (obj.bool("isSidechain") == true) return null
        return textOf(obj.obj("message")?.get("content"))
    }

    private fun textOf(content: Any?): String? = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>()
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun stripIdeNote(text: String): String? =
        if (text.startsWith("[IDE] ")) text.substringAfter("\n\n", "").takeIf { it.isNotBlank() } else text

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().take(80)

    companion object {
        private val NON_ALNUM = Regex("[^A-Za-z0-9]")

        fun default(): SessionStore {
            val home = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".claude")
            return SessionStore(home)
        }
    }
}
