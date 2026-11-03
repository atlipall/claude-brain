package claudechat.ui

import claudechat.cli.arr
import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.context.EditorContextService
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.PromptHistory
import claudechat.session.SessionState
import claudechat.settings.ClaudeChatSettings
import claudechat.testutil.FakeConnection
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ChatBridgeTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private var newChatRequests = 0
    private lateinit var chat: Chat
    private lateinit var bridge: ChatBridge
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    private val settings get() = ClaudeChatSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedSchedule: (Runnable) -> Unit

    override fun setUp() {
        super.setUp()
        PromptHistory.getInstance(project).clear()
        chats.closeAll()
        manager.openFiles.forEach(manager::closeFile) // no editor, no chip, unless a test opens one
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedSchedule = contextService.schedule
        contextService.schedule = { it.run() }
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try {
            bridge.dispose()
            chat.detach(bridge)
            chats.closeAll()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.schedule = savedSchedule
        } finally {
            super.tearDown()
        }
    }

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeHistoryResetAndState() {
        PromptHistory.getInstance(project).add("earlier")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "state"), types())
        assertEquals(listOf(JsonPrimitive("earlier")), posted[1].arr("entries")!!.toList())
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

    fun testResolvePathsUsesTheChatsFolder() {
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/z.txt", "a").virtualFile
        val wtChat = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wtPosted = mutableListOf<JsonObject>()
        val wtBridge = ChatBridge(project, wtChat, {}) { wtPosted += parseJsonObject(it)!! }
        wtBridge.handle("""{"type":"resolvePaths","reqId":1,"candidates":["lib/z.txt"]}""")
        assertEquals(inWorktree.url, wtPosted.single().obj("paths")!!.obj("lib/z.txt")!!.str("target"))
        bridge.handle("""{"type":"resolvePaths","reqId":2,"candidates":["lib/z.txt"]}""")
        assertNull(posted.single().obj("paths")!!["lib/z.txt"])
    }

    fun testSentTextIsRememberedAndBroadcastToOtherTabs() {
        val other = chats.open(ChatSpec.inProject(root))
        val otherPosted = mutableListOf<JsonObject>()
        val otherBridge = ChatBridge(project, other, {}) { otherPosted += parseJsonObject(it)!! }
        try {
            otherBridge.handle("""{"type":"ready"}""")
            bridge.handle("""{"type":"send","text":"hello"}""")
            assertEquals(listOf("hello"), PromptHistory.getInstance(project).entries())
            val last = otherPosted.last { it.str("type") == "history" }
            assertEquals(listOf(JsonPrimitive("hello")), last.arr("entries")!!.toList())
        } finally {
            otherBridge.dispose()
        }
    }

    fun testSendToADeadChatIsNotRemembered() {
        connections[0].exit(1, "boom")
        assertEquals(SessionState.DEAD, chat.session.state)
        bridge.handle("""{"type":"send","text":"ignored"}""")
        assertTrue(PromptHistory.getInstance(project).entries().isEmpty())
        assertTrue(connections[0].userMessages().isEmpty())
    }

    fun testCheckoutIsPostedAsText() {
        bridge.handle("""{"type":"ready"}""")
        chat.showCheckout("IDE: wt1")
        val msg = posted.last()
        assertEquals("checkout", msg.str("type"))
        assertEquals("IDE: wt1", msg.str("text"))
    }

    fun testReadyReplaysTheCheckout() {
        chat.showCheckout("IDE: main")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "state", "checkout"), types())
    }

    fun testDisposedBridgeStopsReceivingHistory() {
        bridge.handle("""{"type":"ready"}""")
        bridge.dispose()
        val before = posted.size
        PromptHistory.getInstance(project).add("later")
        assertEquals(before, posted.size)
    }

    fun testReadyPostsTheEditorContextChip() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        bridge.handle("""{"type":"ready"}""")
        val msg = posted.last()
        assertEquals("context", msg.str("type"))
        assertEquals("calc.js › total · L2", msg.str("text"))
        assertEquals(false, msg.bool("off"))
    }

    fun testContextOffMutesTheChipAndSkipsOneMessage() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"contextOff","off":true}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        assertEquals(true, posted.last().bool("off"))
        bridge.handle("""{"type":"send","text":"hello"}""")
        val sent = connections[0].userMessages().single()
        assertTrue(sent.endsWith("hello"))
        assertFalse(sent, sent.contains("The user is looking at"))
        assertEquals(false, posted.last { it.str("type") == "context" }.bool("off"))
    }

    fun testContextOffCanBeUndone() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"contextOff","off":true}""")
        bridge.handle("""{"type":"contextOff","off":false}""")
        assertEquals(false, posted.last().bool("off"))
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertTrue(connections[0].userMessages().single().contains("[IDE] The user is looking at a.txt, caret at line 1:1."))
    }

    fun testNonBooleanContextOffIsIgnored() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        val count = posted.size
        bridge.handle("""{"type":"contextOff","off":"yes"}""")
        bridge.handle("""{"type":"contextOff"}""")
        assertEquals(count, posted.size)
        assertFalse(chat.contextOff)
    }

    fun testCaretMovesUpdateTheChipOnlyWhenItsTextChanges() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        bridge.handle("""{"type":"ready"}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        myFixture.editor.caretModel.moveToOffset(5)
        assertEquals("a.txt · L2", posted.last().str("text"))
        val count = posted.size
        myFixture.editor.caretModel.moveToOffset(6) // same line, same chip
        assertEquals(count, posted.size)
    }

    fun testSettingOffHidesTheChip() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        settings.attachEditorContext = false
        chat.refreshContext()
        assertEquals("context", posted.last().str("type"))
        assertEquals("", posted.last().str("text"))
    }
}
