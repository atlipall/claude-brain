package claudechat.ui

import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.SessionState
import claudechat.testutil.FakeConnection
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject

class ChatBridgeTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private var newChatRequests = 0
    private lateinit var chat: Chat
    private lateinit var bridge: ChatBridge
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    override fun setUp() {
        super.setUp()
        chats.closeAll()
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try { chat.detach(bridge); chats.closeAll() } finally { super.tearDown() }
    }

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeResetAndState() {
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "reset", "state"), types())
        assertEquals("IDLE", posted.last().str("state"))
    }

    fun testSendReachesThisChatsCliOnly() {
        val other = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1"))
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertTrue(connections[0].userMessages().single().endsWith("hello"))
        assertTrue(connections[1].userMessages().isEmpty())
        assertEquals("user", posted.last { it.str("type") == "item" }.obj("item")!!.str("kind"))
        assertEquals("RUNNING", posted.last { it.str("type") == "state" }.str("state"))
        assertEquals(SessionState.IDLE, other.session.state)
    }

    fun testSetModeGoesToThisChat() {
        chats.open(ChatSpec.inProject(root))
        bridge.handle("""{"type":"setMode","mode":"plan"}""")
        assertEquals("set_permission_mode", connections[0].sentJson().last().obj("request")!!.str("subtype"))
        assertEquals("initialize", connections[1].sentJson().last().obj("request")!!.str("subtype"))
    }

    fun testNewChatAsksTheTabs() {
        bridge.handle("""{"type":"newChat"}""")
        assertEquals(1, newChatRequests)
        assertTrue(posted.isEmpty())
    }

    fun testResolvePathsReturnsOnlyExistingFiles() {
        val file = myFixture.addFileToProject("src/x.txt", "a").virtualFile
        bridge.handle("""{"type":"resolvePaths","reqId":7,"candidates":["src/x.txt:1","nope.txt"]}""")
        val msg = posted.single()
        assertEquals("resolved", msg.str("type"))
        val paths = msg.obj("paths")!!
        assertEquals(file.url, paths.obj("src/x.txt:1")!!.str("target"))
        assertNull(paths["nope.txt"])
    }

    fun testUnknownMalformedAndRemovedMessagesAreIgnored() {
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        bridge.handle("""{"type":"listSessions"}""")
        bridge.handle("""{"type":"resume","sessionId":"abc"}""")
        assertTrue(posted.isEmpty())
        assertEquals(1, connections.size)
    }
}
