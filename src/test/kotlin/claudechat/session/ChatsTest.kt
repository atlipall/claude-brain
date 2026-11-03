package claudechat.session

import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.context.EditorContextService
import claudechat.settings.ClaudeChatSettings
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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

    private val settings get() = ClaudeChatSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedProjectDir: String

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        manager.openFiles.forEach(manager::closeFile) // a leftover editor would add editor context to every message
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedProjectDir = contextService.projectDir
        contextService.projectDir = root
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        chats.store = SessionStore(home)
        FileDocumentManager.getInstance().saveAllDocuments() // chats opened below only hear about later saves
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            home.deleteRecursively()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.projectDir = savedProjectDir
        } finally {
            super.tearDown()
        }
    }

    fun testOpenLaunchesInTheSpecFolder() {
        val chat = chats.open(ChatSpec.inProject("/work/proj"))
        assertEquals("/work/proj", launches.single().cwd)
        assertNull(launches.single().worktree)
        assertEquals("/work/proj", chat.cwd)
        assertEquals("main", chat.title)
        assertEquals(listOf(chat), chats.all())
    }

    fun testNewWorktreeChatUsesPredictedFolderUntilInitReportsOne() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        assertEquals("/work/proj/.claude/worktrees/wt-1", chat.cwd)
        assertEquals("/work/proj", launches.single().cwd)
        assertEquals("wt-1", launches.single().worktree)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals("/elsewhere/real", chat.cwd)
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

    fun testCwdChangeIsReportedOnlyWhenTheFolderChanges() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        var changes = 0
        chat.onCwdChanged = { changes++ }
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals(0, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
    }

    fun testCheckoutTextIsReplayedOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.showCheckout("IDE: wt1")
        val view = object : SessionView by RecordingView() {
            val checkouts = mutableListOf<String>()
            override fun checkout(text: String) { checkouts += text }
        }
        chat.attach(view)
        assertEquals(listOf("IDE: wt1"), view.checkouts)
        chat.showCheckout("IDE: main")
        chat.showCheckout("IDE: main")
        assertEquals(listOf("IDE: wt1", "IDE: main"), view.checkouts)
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

    fun testEditNotesFollowEachChatsFolder() {
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        myFixture.openFileInEditor(myFixture.addFileToProject(".claude/worktrees/wt1/b.txt", "x").virtualFile)
        myFixture.type("y")
        main.send("first")
        wt.send("second")
        assertEquals(listOf("first"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().single().startsWith("[IDE] Since your last turn the user edited these files: b.txt."))
    }

    fun testChatOpenedLaterIsNotToldAboutEarlierSaves() {
        settings.attachEditorContext = false // this is about the edit note only
        myFixture.configureByText("early.txt", "x")
        myFixture.type("y")
        FileDocumentManager.getInstance().saveAllDocuments()
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        assertEquals(listOf("hi"), connections.single().userMessages())
    }

    fun testSendPrefixesEditorContext() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("what does this do?")
        assertEquals(
            listOf("[IDE] The user is looking at calc.js, caret at line 2:11, inside function `total` (lines 1–3).\n\nwhat does this do?"),
            connections.single().userMessages())
    }

    fun testEditNoteComesBeforeEditorContext() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        assertEquals(
            listOf("[IDE] Since your last turn the user edited these files: a.txt.\nRe-read them before relying on earlier contents.\n\n" +
                "[IDE] The user is looking at a.txt, caret at line 1:2.\n\nhello"),
            connections.single().userMessages())
    }

    fun testSkippedContextIsLeftOutOnceThenComesBack() {
        myFixture.configureByText("a.txt", "x")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.skipContext(true)
        chat.send("one")
        assertFalse(chat.contextOff)
        connections.single().emit(Fx.result())
        chat.send("two")
        assertEquals(listOf("one", "[IDE] The user is looking at a.txt, caret at line 1:1.\n\ntwo"), connections.single().userMessages())
    }

    fun testSkipCanBeUndone() {
        myFixture.configureByText("a.txt", "x")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.skipContext(true)
        chat.skipContext(false)
        chat.send("one")
        assertEquals(listOf("[IDE] The user is looking at a.txt, caret at line 1:1.\n\none"), connections.single().userMessages())
    }

    fun testSettingOffSendsNoContext() {
        myFixture.configureByText("a.txt", "x")
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hello")
        assertEquals(listOf("hello"), connections.single().userMessages())
    }

    fun testWorktreeChatNamesTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.txt", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.txt", "x").virtualFile)
        val wt1 = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wt2 = chats.open(ChatSpec("wt2", "$root/.claude/worktrees/wt2"))
        wt1.send("a")
        wt2.send("b")
        assertEquals(listOf("[IDE] The user is looking at src/a.txt, caret at line 1:1.\n\na"), connections[0].userMessages())
        assertEquals(listOf("b"), connections[1].userMessages())
    }
}
