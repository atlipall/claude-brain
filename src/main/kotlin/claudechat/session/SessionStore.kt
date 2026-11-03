package claudechat.session

import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** A past session; [folder] is the cwd it ran in (its transcript lives under that folder's project dir). */
data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long, val folder: String)

/** Reads Claude Code's on-disk session transcripts (~/.claude/projects/<encoded cwd>/<id>.jsonl). */
class SessionStore(private val claudeHome: File) {
    fun projectDir(cwd: String): File = File(File(claudeHome, "projects"), cwd.replace(NON_ALNUM, "-"))

    fun list(cwd: String, limit: Int = 30): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> firstUserText(f)?.let { SessionSummary(f.nameWithoutExtension, oneLine(it), f.lastModified(), cwd) } }
            .take(limit)
            .toList()

    /** Sessions of every folder, newest first. Folders that map to the same project dir are read once. */
    fun listAll(folders: List<String>, limit: Int = 50): List<SessionSummary> =
        folders.distinctBy { projectDir(it) }
            .flatMap { list(it, limit) }
            .sortedByDescending { it.modifiedMillis }
            .take(limit)

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

    /** The text the user typed, under one `[image]` per image they attached (the plugin keeps no image data). */
    private fun userText(obj: JsonObject): String? {
        if (obj.bool("isMeta") == true || obj.bool("isSidechain") == true) return null
        val content = obj.obj("message")?.get("content")
        val text = textOf(content)
        if (text != null && (text.startsWith("<") || text.startsWith("[Request interrupted"))) return null
        val typed = text?.let(::stripIdeNote)
        val images = (content as? JsonArray).orEmpty().count { (it as? JsonObject)?.str("type") == "image" }
        if (images == 0) return typed
        val marks = List(images) { IMAGE_MARK }.joinToString(" ")
        return if (typed == null) marks else "$marks\n$typed"
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

    /** Drops every leading "[IDE] " note (a message can carry an edit note and a context note); null when only notes remain. */
    private fun stripIdeNote(text: String): String? {
        var rest = text
        while (rest.startsWith("[IDE] ")) rest = rest.substringAfter("\n\n", "")
        return rest.takeIf { it.isNotBlank() }
    }

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().take(80)

    companion object {
        private val NON_ALNUM = Regex("[^A-Za-z0-9]")
        const val IMAGE_MARK = "[image]"

        fun default(): SessionStore {
            val home = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".claude")
            return SessionStore(home)
        }
    }
}
