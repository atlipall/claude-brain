package claudechat.session

import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.testutil.FakeConnection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class SessionManagerTest : BasePlatformTestCase() {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var home: File
    private val manager get() = SessionManager.getInstance(project)

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
        home = Files.createTempDirectory("claude-home").toFile()
        manager.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        manager.store = SessionStore(home)
        manager.newChat()
    }

    override fun tearDown() {
        try { manager.current?.dispose(); home.deleteRecursively() } finally { super.tearDown() }
    }

    fun testAttachReplaysStateOfCurrentSession() {
        val view = RecordingView()
        manager.attach(view)
        assertEquals(listOf(emptyList<ChatItem>()), view.resets)
        assertEquals(SessionState.IDLE, view.states.last())
        manager.detach(view)
    }

    fun testSendPrefixesEditNote() {
        val view = RecordingView()
        manager.attach(view)
        myFixture.configureByText("a.ts", "x")
        myFixture.type("y")
        manager.send("hello")
        val content = connections.last().userMessages().single()
        assertTrue(content.startsWith("[IDE] Since your last turn the user edited these files: a.ts."))
        assertTrue(content.endsWith("\n\nhello"))
        assertEquals("hello", (view.items.last { it is ChatItem.UserText } as ChatItem.UserText).text)
        manager.detach(view)
    }

    fun testNewChatStopsPreviousConnection() {
        val first = connections.last()
        manager.newChat()
        assertTrue(first.stopped)
        assertNotSame(first, connections.last())
    }

    fun testResumeLoadsHistoryAndPassesResumeId() {
        val dir = manager.store.projectDir(project.basePath!!).apply { mkdirs() }
        File(dir, "abc.jsonl").writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val view = RecordingView()
        manager.attach(view)
        manager.resume("abc")
        assertEquals("abc", launches.last().resumeId)
        assertEquals(listOf(ChatItem.UserText("h-0", "earlier")), view.resets.last())
        assertEquals(listOf("abc"), manager.listSessions().map { it.id })
        manager.detach(view)
    }

    fun testInsertBeforeAttachIsDeliveredOnAttach() {
        manager.insert("@src/a.ts#L1-2")
        val view = RecordingView()
        manager.attach(view)
        assertEquals(listOf("@src/a.ts#L1-2"), view.inserted)
        manager.detach(view)
    }
}
