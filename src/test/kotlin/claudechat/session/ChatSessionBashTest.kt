package claudechat.session

import claudechat.cli.arr
import claudechat.cli.obj
import claudechat.cli.str
import claudechat.git.WorkingTrees
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import claudechat.testutil.RecordingListener
import claudechat.testutil.git
import claudechat.testutil.gitAvailable
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Files changed by Bash calls join the turn's list, found by snapshotting the checkout around each call. */
class ChatSessionBashTest {
    @get:Rule val tmp = TemporaryFolder()
    private val connections = mutableListOf<FakeConnection>()
    private val conn get() = connections.last()
    private val listener = RecordingListener()
    private val deferred = mutableListOf<Runnable>()
    private lateinit var repo: File

    @Before fun setUp() {
        assumeTrue(gitAvailable())
        repo = tmp.newFolder("repo").canonicalFile
        git(repo, "init", "-q")
        File(repo, "a.txt").writeText("one\ntwo\n")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "init")
    }

    private fun session(trees: WorkingTrees? = WorkingTrees()) = ChatSession(
        connect = { FakeConnection().also { connections += it } },
        listener = listener,
        cwd = repo.path,
        background = { deferred += it },
        trees = trees,
    ).also { it.start() }

    private fun runBackground() { deferred.toList().forEach { it.run() }; deferred.clear() }

    private fun hook(requestId: String, callbackId: String, event: String, toolUseId: String) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"hook_callback","callback_id":"$callbackId",""" +
            """"input":{"hook_event_name":"$event","tool_name":"Bash","tool_use_id":"$toolUseId","cwd":${Fx.q(repo.path)},"tool_input":{"command":"x"}}}}"""

    private fun answered(requestId: String) =
        conn.sentJson().any { it.str("type") == "control_response" && it.obj("response")?.str("request_id") == requestId }

    /** One Bash call that runs [change] between its hooks; [after] names the hook event that ends it. */
    private fun bash(id: String, after: String = "PostToolUse", change: () -> Unit) {
        conn.emit(Fx.toolUse("m1", id, "Bash", """{"command":"x"}"""), hook("pre-$id", "before-bash", "PreToolUse", id))
        assertTrue("the call waits for the snapshot", !answered("pre-$id"))
        runBackground()
        assertTrue(answered("pre-$id"))
        change()
        conn.emit(hook("post-$id", "after-bash", after, id))
        runBackground()
        assertTrue(answered("post-$id"))
        conn.emit(Fx.toolResult(id, "ok"))
    }

    private fun turnFiles() = listener.latest<ChatItem.TurnResult>().files

    @Test fun initializeAsksForHooksAroundBash() {
        session()
        val hooks = conn.sentJson().first { it.obj("request")?.str("subtype") == "initialize" }.obj("request")!!.obj("hooks")!!
        for (event in listOf("PreToolUse", "PostToolUse", "PostToolUseFailure")) {
            assertEquals("Bash", hooks.arr(event)!!.single().jsonObject.str("matcher"))
        }
    }

    @Test fun filesABashCallChangedAreListedAgainstTheirContentBeforeIt() {
        val s = session()
        s.send("x")
        bash("t1") {
            File(repo, "a.txt").writeText("one\n2\n")
            File(repo, "new.txt").writeText("n\n")
        }
        conn.emit(Fx.result())
        runBackground()
        val a = File(repo, "a.txt").path
        val n = File(repo, "new.txt").path
        assertEquals(listOf(TurnFile(a, added = 1, removed = 1, isNew = false), TurnFile(n, added = 1, removed = 0, isNew = true)), turnFiles())
        assertEquals(listOf(FileDiff(a, "one\ntwo\n"), FileDiff(n, "")), s.turnDiffs(listener.latest<ChatItem.TurnResult>().id))
    }

    @Test fun aFailedCallsChangesCountToo() {
        session().send("x")
        bash("t1", after = "PostToolUseFailure") { File(repo, "a.txt").writeText("changed\n") }
        conn.emit(Fx.result())
        runBackground()
        assertEquals(listOf(File(repo, "a.txt").path), turnFiles().map { it.path })
    }

    @Test fun aFileChangedTwiceKeepsItsContentFromBeforeTheTurn() {
        val s = session()
        s.send("x")
        bash("t1") { File(repo, "a.txt").writeText("first\n") }
        bash("t2") { File(repo, "a.txt").writeText("second\n") }
        conn.emit(Fx.result())
        runBackground()
        assertEquals(listOf(FileDiff(File(repo, "a.txt").path, "one\ntwo\n")), s.turnDiffs(listener.latest<ChatItem.TurnResult>().id))
    }

    @Test fun withoutSnapshotsTheHooksAreNotAskedFor() {
        session(trees = null)
        val req = conn.sentJson().first { it.obj("request")?.str("subtype") == "initialize" }.obj("request")!!
        assertEquals(kotlinx.serialization.json.JsonNull, req["hooks"])
    }
}
