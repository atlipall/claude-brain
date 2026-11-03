package claudechat.ui

import claudechat.cli.arr
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.SessionManager
import claudechat.testutil.FakeConnection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject

class ChatBridgeTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var bridge: ChatBridge
    private val manager get() = SessionManager.getInstance(project)

    override fun setUp() {
        super.setUp()
        manager.connector = { FakeConnection().also { connections += it } }
        manager.newChat()
        bridge = ChatBridge(project) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try { manager.detach(bridge); manager.current?.dispose() } finally { super.tearDown() }
    }

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeResetAndState() {
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "reset", "state"), types())
        assertEquals("IDLE", posted.last().str("state"))
    }

    fun testSendReachesCliAndEchoesItem() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertEquals(listOf("hello"), connections.last().userMessages())
        val item = posted.last { it.str("type") == "item" }.obj("item")!!
        assertEquals("user", item.str("kind"))
        assertEquals("RUNNING", posted.last { it.str("type") == "state" }.str("state"))
    }

    fun testResolvePathsReturnsOnlyExistingFiles() {
        val file = myFixture.addFileToProject("src/x.ts", "a").virtualFile
        bridge.handle("""{"type":"resolvePaths","reqId":7,"candidates":["src/x.ts:1","nope.ts"]}""")
        val msg = posted.single()
        assertEquals("resolved", msg.str("type"))
        val paths = msg.obj("paths")!!
        assertEquals(file.url, paths.obj("src/x.ts:1")!!.str("target"))
        assertNull(paths["nope.ts"])
    }

    fun testListSessionsPostsSessions() {
        bridge.handle("""{"type":"listSessions"}""")
        assertEquals("sessions", posted.single().str("type"))
        assertNotNull(posted.single().arr("sessions"))
    }

    fun testUnknownOrMalformedMessagesAreIgnored() {
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        assertTrue(posted.isEmpty())
    }
}
