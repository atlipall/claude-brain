package claudechat.session

import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

/**
 * A past session; [folder] is the cwd it ran in (its transcript lives under that folder's project dir). [title] is its
 * first message; [customTitle] the name it was given (in Tether or with Claude Code's `/rename`), if any. An [archived]
 * session is hidden from the resume list.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val modifiedMillis: Long,
    val folder: String,
    val customTitle: String? = null,
    val archived: Boolean = false,
)

/** Reads Claude Code's on-disk session transcripts (~/.claude/projects/<encoded cwd>/<id>.jsonl). */
class SessionStore(private val claudeHome: File) {
    fun projectDir(cwd: String): File = File(File(claudeHome, "projects"), cwd.replace(NON_ALNUM, "-"))

    private fun transcriptFile(cwd: String, sessionId: String) = File(projectDir(cwd), "$sessionId.jsonl")

    /** The folder's sessions, newest first: the archived ones when [archived], otherwise the rest. */
    fun list(cwd: String, limit: Int = 30, archived: Boolean = false): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> cachedSummary(f, cwd) }
            .filter { it.archived == archived }
            .take(limit)
            .toList()

    /** Sessions of every folder, newest first. Folders that map to the same project dir are read once. */
    fun listAll(folders: List<String>, limit: Int = 50, archived: Boolean = false): List<SessionSummary> =
        folders.distinctBy { projectDir(it) }
            .flatMap { list(it, limit, archived) }
            .sortedByDescending { it.modifiedMillis }
            .take(limit)

    fun transcript(cwd: String, sessionId: String): List<ChatItem> {
        val file = transcriptFile(cwd, sessionId)
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

    /** The uuid of the transcript's newest main-conversation assistant message (subagents' are skipped), or null. */
    fun lastAssistantUuid(cwd: String, sessionId: String): String? {
        val file = transcriptFile(cwd, sessionId)
        if (!file.isFile) return null
        return file.useLines { lines ->
            lines.mapNotNull(::parseJsonObject)
                .filter { it.str("type") == "assistant" && it.bool("isSidechain") != true }
                .mapNotNull { it.str("uuid") }
                .lastOrNull()
        }
    }

    /** When the session began: the first timestamp in its transcript (epoch millis), or null. */
    fun startedAt(cwd: String, sessionId: String): Long? {
        val file = transcriptFile(cwd, sessionId)
        if (!file.isFile) return null
        return file.useLines { lines ->
            lines.mapNotNull { parseJsonObject(it)?.str("timestamp") }
                .mapNotNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                .firstOrNull()
        }
    }

    /**
     * Names the session [title] the way Claude Code's `/rename` does: a `custom-title` line appended to its transcript
     * (the last one wins; blank means none). [archived] prefixes it with `[archived]`, as other Claude apps do.
     * Safe while claude appends to the same file: each write is one whole line.
     */
    fun saveTitle(cwd: String, sessionId: String, title: String, archived: Boolean = false) {
        val file = transcriptFile(cwd, sessionId)
        file.parentFile.mkdirs() // claude may not have written the transcript yet; it appends to this one
        val line = buildJsonObject {
            put("type", CUSTOM_TITLE)
            put("customTitle", if (archived) "${ChatNames.ARCHIVED} $title".trimEnd() else title)
            put("sessionId", sessionId)
        }.toString()
        RandomAccessFile(file, "rw").use { f ->
            val end = f.length()
            val midLine = end > 0 && f.run { seek(end - 1); read() != '\n'.code }
            f.seek(end)
            f.write(((if (midLine) "\n" else "") + line + "\n").toByteArray())
        }
    }

    private class Scanned(val modified: Long, val length: Long, val summary: SessionSummary?)

    /** Summaries by transcript path, read again only when the file changes: finding the title means reading it all. */
    private val scanned = ConcurrentHashMap<String, Scanned>()

    private fun cachedSummary(file: File, cwd: String): SessionSummary? {
        val modified = file.lastModified()
        val length = file.length()
        scanned[file.path]?.takeIf { it.modified == modified && it.length == length }?.let { return it.summary }
        return summary(file, cwd).also { scanned[file.path] = Scanned(modified, length, it) }
    }

    /**
     * The first message and the last custom title; null when the session has neither. A title alone is kept: an archived
     * chat's claude can be stopped before it writes the first message.
     */
    private fun summary(file: File, cwd: String): SessionSummary? {
        var first: String? = null
        var custom: String? = null
        var archived = false
        file.useLines { lines ->
            for (line in lines) {
                val titled = line.contains(TITLE_TYPE)
                if (first != null && !titled) continue // only titles matter now; skip parsing the rest
                val obj = parseJsonObject(line) ?: continue
                when (obj.str("type")) {
                    "user" -> if (first == null) first = userText(obj)
                    CUSTOM_TITLE -> {
                        val raw = obj.str("customTitle").orEmpty().trim()
                        archived = raw.startsWith(ChatNames.ARCHIVED)
                        custom = ChatNames.clean(raw)
                    }
                }
            }
        }
        val title = first?.let { ChatNames.clean(it)!!.take(80) } ?: custom ?: return null
        return SessionSummary(file.nameWithoutExtension, title, file.lastModified(), cwd, custom, archived)
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


    companion object {
        private val NON_ALNUM = Regex("[^A-Za-z0-9]")
        const val IMAGE_MARK = "[image]"
        private const val CUSTOM_TITLE = "custom-title"
        /** How a title line spells its type: cheaper to find than parsing every line, and rarer than the bare word. */
        private const val TITLE_TYPE = "\"$CUSTOM_TITLE\""

        fun default(): SessionStore {
            val home = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".claude")
            return SessionStore(home)
        }
    }
}
