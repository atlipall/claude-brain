package claudechat.session

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cwd = "/Users/me/my_proj.v2"

    private fun write(store: SessionStore, id: String, modified: Long, vararg lines: String): File =
        writeIn(store, cwd, id, modified, *lines)

    private fun writeIn(store: SessionStore, folder: String, id: String, modified: Long, vararg lines: String): File {
        val dir = store.projectDir(folder).apply { mkdirs() }
        return File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n")); setLastModified(modified) }
    }

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(text)}},"sessionId":"s"}"""
    private fun meta(text: String) = """{"type":"user","isMeta":true,"message":{"role":"user","content":"$text"}}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""
    /** A user line as claude 2.1.317 writes it for a message with an image: text block, image block (data trimmed). */
    private fun userWithImages(text: String?, images: Int): String {
        val blocks = listOfNotNull(text?.let { """{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(it)}}""" }) +
            List(images) { """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}""" }
        return """{"type":"user","message":{"role":"user","content":[${blocks.joinToString(",")}]},"imagePasteIds":[1],"sessionId":"s"}"""
    }
    private fun imageCompanion() = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Image: source: /tmp/s/images/1.png]"}]},"isMeta":true}"""
    private val readImageResult = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t","type":"tool_result","content":[{"type":"image","source":{"type":"base64","data":"iVBORw0KGgo=","media_type":"image/png"}}]}]}}"""
    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"x"}]}}"""

    @Test fun projectDirEncodesCwd() {
        val store = SessionStore(tmp.root)
        assertEquals(File(tmp.root, "projects/-Users-me-my-proj-v2"), store.projectDir(cwd))
    }

    @Test fun listsNewestFirstWithFirstRealUserMessageAsTitle() {
        val store = SessionStore(tmp.root)
        write(store, "old", 1_000_000, """{"type":"session-owner"}""", user("first question"))
        write(store, "new", 2_000_000, meta("<command-name>"), user("<local-command>"), user("second\nquestion"))
        write(store, "empty", 3_000_000, """{"type":"session-owner"}""")
        assertEquals(
            listOf(SessionSummary("new", "second question", 2_000_000, cwd), SessionSummary("old", "first question", 1_000_000, cwd)),
            store.list(cwd))
    }

    @Test fun listOfUnknownProjectIsEmpty() = assertEquals(emptyList<SessionSummary>(), SessionStore(tmp.root).list("/nowhere"))

    @Test fun transcriptKeepsUserAndAssistantTextAndStripsIdeNote() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1, user("hello"), assistant("hi there"), toolResult, user("[IDE] Since your last turn...\nRe-read.\n\nnext one"), assistant("ok"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "hello"),
                ChatItem.AssistantText("h-1", "hi there", false),
                ChatItem.UserText("h-2", "next one"),
                ChatItem.AssistantText("h-3", "ok", false),
            ),
            store.transcript(cwd, "s1"))
    }

    @Test fun transcriptStripsEveryIdeNote() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1,
            user("[IDE] Since your last turn...\nRe-read.\n\n[IDE] The user is looking at `a.ts`.\n\nboth"),
            assistant("a"),
            user("[IDE] The user is looking at `a.ts`.\n\nctx only"),
            assistant("b"),
            user("[IDE] Since your last turn...\n\n[IDE] The user is looking at `a.ts`."),
            assistant("c"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "both"),
                ChatItem.AssistantText("h-1", "a", false),
                ChatItem.UserText("h-2", "ctx only"),
                ChatItem.AssistantText("h-3", "b", false),
                ChatItem.AssistantText("h-4", "c", false),
            ),
            store.transcript(cwd, "s1"))
    }

    @Test fun listTitleUsesTheCleanedText() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000, user("[IDE] edit\n\n[IDE] ctx\n\nreal question"))
        assertEquals("real question", store.list(cwd).single().title)
    }

    @Test fun listAllMergesFoldersNewestFirst() {
        val store = SessionStore(tmp.root)
        val wt = "$cwd/.claude/worktrees/wt-1"
        writeIn(store, cwd, "a", 1_000_000, user("in project"))
        writeIn(store, wt, "b", 3_000_000, user("in worktree"))
        writeIn(store, cwd, "c", 2_000_000, user("later in project"))
        assertEquals(
            listOf(
                SessionSummary("b", "in worktree", 3_000_000, wt),
                SessionSummary("c", "later in project", 2_000_000, cwd),
                SessionSummary("a", "in project", 1_000_000, cwd),
            ),
            store.listAll(listOf(cwd, wt, cwd, "/nowhere")))
    }

    @Test fun listAllHonoursLimit() {
        val store = SessionStore(tmp.root)
        writeIn(store, cwd, "a", 1_000_000, user("one"))
        writeIn(store, "$cwd/.claude/worktrees/wt-1", "b", 2_000_000, user("two"))
        assertEquals(listOf("b"), store.listAll(listOf(cwd, "$cwd/.claude/worktrees/wt-1"), limit = 1).map { it.id })
    }

    @Test fun transcriptShowsImagePlaceholdersAboveTheText() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1,
            userWithImages("[IDE] The user is looking at a.ts.\n\nwhat is this?", 2), imageCompanion(), assistant("a chart"),
            userWithImages(null, 1), imageCompanion(), assistant("ok"),
            userWithImages("[IDE] The user is looking at a.ts.", 1), readImageResult, assistant("b"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "[image] [image]\nwhat is this?"),
                ChatItem.AssistantText("h-1", "a chart", false),
                ChatItem.UserText("h-2", "[image]"),
                ChatItem.AssistantText("h-3", "ok", false),
                ChatItem.UserText("h-4", "[image]"),
                ChatItem.AssistantText("h-5", "b", false),
            ),
            store.transcript(cwd, "s1"))
    }

    @Test fun listTitleOfAnImageMessage() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1_000_000, userWithImages("look", 1))
        assertEquals("[image] look", store.list(cwd).single().title)
    }
}
