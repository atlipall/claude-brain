package claudechat.session

import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.ide.EditTracker
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class ChatsTest : BasePlatformTestCase() {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var home: File
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    private class RecordingView : SessionView {
        val resets = mutableListOf<List<ChatItem>>()
        val items = mutableListOf<ChatItem>()
        val states = mutableListOf<SessionState>()
        val inserted = mutableListOf<String>()
        override fun reset(items: List<ChatItem>) { resets += items }
        override fun item(item: ChatItem) { items += item }
        override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) { states += state }
        override fun insert(text: String) { inserted += text }
    }

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        chats.store = SessionStore(home)
        EditTracker.getInstance(project).consumeNote() // drop saves recorded by earlier tests
    }

    override fun tearDown() {
        try { chats.closeAll(); home.deleteRecursively() } finally { super.tearDown() }
    }

    fun testOpenLaunchesInTheSpecFolder() {
        val chat = chats.open(ChatSpec.inProject("/work/proj"))
        assertEquals("/work/proj", launches.single().cwd)
        assertNull(launches.single().worktree)
        assertEquals("/work/proj", chat.cwd)
        assertEquals("main", chat.title)
        assertEquals(listOf(chat), chats.all())
    }

    fun testNewWorktreeChatPassesNameAndAdoptsReportedFolder() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        assertEquals("wt-1", launches.single().worktree)
        assertEquals("/work/proj", launches.single().cwd)
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals("/work/proj/.claude/worktrees/wt-1", chat.cwd)
        assertEquals("wt-1", chat.title)
    }

    fun testChatsAreIndependent() {
        val a = chats.open(ChatSpec.inProject(root))
        val b = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1"))
        val viewA = RecordingView().also(a::attach)
        val viewB = RecordingView().also(b::attach)
        a.send("hello")
        assertEquals(listOf("hello"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().isEmpty())
        assertTrue(viewA.items.any { it is ChatItem.UserText })
        assertTrue(viewB.items.isEmpty())
        assertEquals(SessionState.IDLE, b.session.state)
    }

    fun testCloseStopsOnlyThatChat() {
        val a = chats.open(ChatSpec.inProject(root))
        val b = chats.open(ChatSpec.inProject(root))
        chats.close(a)
        assertTrue(connections[0].stopped)
        assertFalse(connections[1].stopped)
        assertEquals(listOf(b), chats.all())
    }

    fun testAttachReplaysItemsAndState() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("hi"), view.resets.single().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(SessionState.RUNNING, view.states.last())
    }

    fun testInsertBeforeAttachIsDeliveredOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.insert("@src/a.txt#L1-2")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("@src/a.txt#L1-2"), view.inserted)
        chat.insert("@b.txt")
        assertEquals(listOf("@src/a.txt#L1-2", "@b.txt"), view.inserted)
    }

    fun testResumeLoadsHistoryFromTheSessionFolder() {
        val folder = "/work/proj/.claude/worktrees/wt-1"
        File(chats.store.projectDir(folder).apply { mkdirs() }, "abc.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val chat = chats.open(ChatSpec.resume("/work/proj", SessionSummary("abc", "earlier", 0, folder)))
        assertEquals("abc", launches.single().resumeId)
        assertEquals(folder, launches.single().cwd)
        val view = RecordingView().also(chat::attach)
        assertEquals(listOf(ChatItem.UserText("h-0", "earlier")), view.resets.single())
        assertEquals("wt-1 · earlier", chat.title)
    }

    fun testFirstSentMessageSetsTitleOnce() {
        val chat = chats.open(ChatSpec.inProject(root))
        var changes = 0
        chat.onTitleChanged = { changes++ }
        chat.send("Fix the flaky login test in the auth module")
        chat.send("and another")
        assertEquals("main · Fix the flaky login test in th…", chat.title)
        assertEquals(1, changes)
    }

    fun testDeadChatIgnoresSend() {
        val chat = chats.open(ChatSpec.inProject(root))
        connections.single().exit(1, "gone")
        chat.send("lost")
        assertTrue(connections.single().userMessages().isEmpty())
        assertEquals("main", chat.title)
    }

    fun testSendPrefixesEditNote() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        val content = connections.single().userMessages().single()
        assertTrue(content, content.startsWith("[IDE] Since your last turn the user edited these files: a.txt."))
        assertTrue(content.endsWith("\n\nhello"))
    }
}
