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
}
