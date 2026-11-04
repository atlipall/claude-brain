package claudechat.ui

import kotlinx.serialization.json.jsonObject
import claudechat.testutil.Fx
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
import claudechat.testutil.TestImages
import com.intellij.openapi.application.WriteAction
import java.io.IOException
import java.util.Base64
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatBridgeTest : BasePlatformTestCase() {
    /** What the bridge posted; appended off the EDT too (session events arrive on reader threads). */
    private val posted = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
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
        manager.openFiles.forEach(manager::closeFile) // no editor, unless a test opens one
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedSchedule = contextService.schedule
        contextService.schedule = { it.run() }
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
        bridge.offEdt = { it() }
        bridge.onEdt = { it() }
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

    fun testShowChangesOpensThisChatsChanges() {
        val shown = mutableListOf<Chat>()
        bridge.showChanges = { shown += it }
        bridge.handle("""{"type":"showChanges"}""")
        assertEquals(listOf(chat), shown)
    }

    fun testATurnsFileOpensItsDiffsAtThatFile() {
        val a = java.io.File.createTempFile("turn-a", ".txt").apply { writeText("2\n"); deleteOnExit() }
        val b = java.io.File.createTempFile("turn-b", ".txt").apply { writeText("2\n"); deleteOnExit() }
        val shown = mutableListOf<Pair<List<String>, Int>>()
        bridge.showTurnDiffs = { diffs, index -> shown += diffs.map { it.filePath } to index }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"go"}""")
        for ((id, file) in listOf("t1" to a, "t2" to b)) connections[0].emit(
            Fx.toolUse("m1", id, "Edit", """{"file_path":${Fx.q(file.path)}}"""),
            Fx.toolResult(id, "ok", toolUseResultJson = Fx.editResult(file.path, "1\n")),
        )
        connections[0].emit(Fx.result())
        // The files are added off the session lock, in a second update of the result item.
        fun withFiles() = posted.lastOrNull { it.str("type") == "item" && it.obj("item")!!.arr("files") != null }?.obj("item")
        val deadline = System.currentTimeMillis() + 5000
        while (withFiles() == null && System.currentTimeMillis() < deadline) PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val result = withFiles()!!
        bridge.handle("""{"type":"openTurnDiff","id":${Fx.q(result.str("id")!!)},"index":1}""")
        bridge.handle("""{"type":"openTurnDiff","id":"result-unknown","index":0}""")
        assertEquals(listOf(listOf(a.path, b.path) to 1), shown)

        val all = mutableListOf<List<String>>()
        bridge.showTurnChanges = { diffs -> all += diffs.map { it.filePath } }
        bridge.handle("""{"type":"openTurnChanges","id":${Fx.q(result.str("id")!!)}}""")
        bridge.handle("""{"type":"openTurnChanges","id":"result-unknown"}""")
        assertEquals(listOf(listOf(a.path, b.path)), all)
    }

    fun testANewChatRecordsWhenItStarted() {
        val before = System.currentTimeMillis()
        val started = chats.open(ChatSpec.inProject(root)).startedAt!!
        assertTrue(started in before..System.currentTimeMillis())
    }

    fun testActivityIsPostedAndReplayedOnReady() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        val first = posted.last { it.str("type") == "activity" }
        assertEquals("starting", first.str("phase"))
        connections[0].emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/a"}"""))
        val running = posted.last { it.str("type") == "activity" }
        assertEquals("running", running.str("phase"))
        assertEquals("Read", running.str("tool"))
        connections[0].emit(Fx.result())
        assertTrue(posted.last { it.str("type") == "activity" }["phase"] is kotlinx.serialization.json.JsonNull)
        posted.clear()
        connections[0].emit(Fx.thinkingStart("m2", 0)) // no turn running here, but the session reports it only while busy
        assertTrue(posted.none { it.str("type") == "activity" })
    }

    fun testToolNameInTheStripIsCleanedAndShortened() {
        val msg = parseJsonObject(ChatBridge.activityMessage(claudechat.session.Activity.Running("mcp__x`\n" + "a".repeat(100))))!!
        val tool = msg.str("tool")!!
        assertTrue(tool.length <= 40)
        assertFalse(tool.contains('`'))
        assertFalse(tool.contains('\n'))
        assertEquals("tool", parseJsonObject(ChatBridge.activityMessage(claudechat.session.Activity.Running("  ")))!!.str("tool"))
    }

    fun testAgentsActivityMessage() {
        val msg = parseJsonObject(ChatBridge.activityMessage(claudechat.session.Activity.Agents(2)))!!
        assertEquals("agents", msg.str("phase"))
        assertEquals("2", msg["count"].toString())
    }

    fun testUsageMessage() {
        val usage = claudechat.cli.ContextUsage(76000, 200000, 38, 83, listOf(
            claudechat.cli.ContextCategory("Messages", 52000),
            claudechat.cli.ContextCategory("Ski`lls\n" + "x".repeat(100), 10000),
            claudechat.cli.ContextCategory("  ", 5),
        ))
        val msg = parseJsonObject(ChatBridge.usageMessage(usage))!!
        assertEquals("usage", msg.str("type"))
        assertEquals("76000", msg["used"].toString())
        assertEquals("200000", msg["max"].toString())
        assertEquals("38", msg["percent"].toString())
        assertEquals("83", msg["autocompactAt"].toString())
        val cats = msg.arr("categories")!!.map { it.jsonObject }
        assertEquals(2, cats.size) // the blank name is dropped
        assertEquals("Messages", cats[0].str("name"))
        val cleaned = cats[1].str("name")!!
        assertTrue(cleaned.length <= 40 && '`' !in cleaned && '\n' !in cleaned)
        assertFalse(parseJsonObject(ChatBridge.usageMessage(usage.copy(autocompactAt = null)))!!.containsKey("autocompactAt"))
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

    fun testReadyPostsNoContextMessage() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        bridge.handle("""{"type":"ready"}""")
        assertTrue(posted.none { it.str("type") == "context" })
    }

    fun testContextOffMessageIsIgnoredAndTheNoteIsStillSent() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        val count = posted.size
        bridge.handle("""{"type":"contextOff","off":true}""")
        assertEquals(count, posted.size)
        bridge.handle("""{"type":"send","text":"hello"}""")
        PlatformTestUtil.waitWithEventsDispatching("Not sent", { connections[0].userMessages().isNotEmpty() }, 10)
        assertTrue(connections[0].userMessages().single().contains("[IDE] The user is looking at a.txt, caret at line 1:1."))
    }

    fun testSettingOffSendsNoNote() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        settings.attachEditorContext = false
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertEquals(listOf("hello"), connections[0].userMessages())
    }

    // --- attachments ---

    private fun upload(id: String, base64: String, name: String = "$id.png") {
        bridge.handle("""{"type":"attachStart","id":"$id","name":"$name","size":${base64.length / 4 * 3}}""")
        for (part in base64.chunked(64)) bridge.handle("""{"type":"attachChunk","id":"$id","data":"$part"}""")
        bridge.handle("""{"type":"attachEnd","id":"$id"}""")
    }

    private fun lastAttachment() = posted.last { it.str("type") == "attachment" }
    private fun notices() = posted.filter { it.str("type") == "item" }.map { it.obj("item")!! }.filter { it.str("kind") == "notice" }.map { it.str("text") }

    fun testUploadedImageIsShownAndSentWithTheMessage() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.RED_PNG_BASE64)
        val shown = lastAttachment()
        assertEquals("p1", shown.str("id"))
        assertEquals("p1.png", shown.str("name"))
        assertEquals("data:image/png;base64,${TestImages.RED_PNG_BASE64}", shown.str("thumb"))
        bridge.handle("""{"type":"send","text":"what color?","attachments":["p1"]}""")
        assertTrue(connections[0].userMessages().single().endsWith("what color?"))
        assertEquals(listOf(listOf(TestImages.RED_PNG_BASE64)), connections[0].userImages())
        val bubble = posted.last { it.str("type") == "item" && it.obj("item")!!.str("kind") == "user" }.obj("item")!!
        assertEquals(listOf(JsonPrimitive("data:image/png;base64,${TestImages.RED_PNG_BASE64}")), bubble.arr("images")!!.toList())
        assertEquals(0, chat.attachments.size)
        assertEquals(listOf("what color?"), PromptHistory.getInstance(project).entries())
    }

    fun testImageOnlyMessageIsSentButNotRemembered() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"send","text":"","attachments":["p1"]}""")
        assertEquals(listOf(listOf(TestImages.BLUE_PNG_BASE64)), connections[0].userImages())
        assertTrue(PromptHistory.getInstance(project).entries().isEmpty())
    }

    fun testBlankMessageWithUnknownAttachmentsIsIgnored() {
        bridge.handle("""{"type":"send","text":"  ","attachments":["nope", 3]}""")
        assertTrue(connections[0].userMessages().isEmpty())
    }

    fun testMagicBytesDecideTheType() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", Base64.getEncoder().encodeToString(TestImages.solid(8, 8, "jpeg")), name = "looks-like.png")
        assertTrue(lastAttachment().str("thumb")!!.startsWith("data:image/jpeg;base64,"))
    }

    fun testNonImageIsRefusedWithANotice() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", Base64.getEncoder().encodeToString("hello world!".toByteArray()), name = "notes.txt")
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach notes.txt: not a PNG, JPEG, GIF or WebP image.", notices().last())
        assertEquals(0, chat.attachments.size)
    }

    fun testFileOver20MbIsRefusedBeforeItsBytesArrive() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"attachStart","id":"p1","name":"huge.png","size":20971521}""")
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach huge.png: it is larger than 20 MB.", notices().last())
        bridge.handle("""{"type":"attachChunk","id":"p1","data":"${TestImages.BLUE_PNG_BASE64}"}""")
        bridge.handle("""{"type":"attachEnd","id":"p1"}""")
        assertEquals(0, chat.attachments.size)
    }

    fun testEleventhImageIsRefused() {
        bridge.handle("""{"type":"ready"}""")
        for (i in 1..10) upload("p$i", TestImages.BLUE_PNG_BASE64)
        assertEquals(10, chat.attachments.size)
        upload("p11", TestImages.BLUE_PNG_BASE64)
        assertEquals("p11", lastAttachment().str("id"))
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals("Couldn't attach p11.png: at most 10 images per message.", notices().last())
        assertEquals(10, chat.attachments.size)
    }

    fun testRemovedAttachmentIsNotSent() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"detach","id":"p1"}""")
        bridge.handle("""{"type":"send","text":"x","attachments":["p1"]}""")
        assertEquals(listOf(emptyList<String>()), connections[0].userImages())
    }

    fun testAttachmentRemovedWhilePreparingIsDropped() {
        val tasks = mutableListOf<() -> Unit>()
        bridge.offEdt = { tasks += it }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"detach","id":"p1"}""")
        tasks.forEach { it() }
        assertTrue(posted.none { it.str("type") == "attachment" })
        assertEquals(0, chat.attachments.size)
    }

    fun testReloadedPageForgetsTheOldPagesAttachments() {
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        bridge.handle("""{"type":"ready"}""")
        assertEquals(0, chat.attachments.size)
    }

    fun testPickedFilesBecomeAttachments() {
        val file = myFixture.addFileToProject("pics/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.chooseImages = { done -> done(listOf(file)) }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"pickImages"}""")
        val shown = lastAttachment()
        assertEquals("k1", shown.str("id"))
        assertEquals("blue.png", shown.str("name"))
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", shown.str("thumb"))
        assertEquals(1, chat.attachments.size)
    }

    fun testPickedFileShowsALoadingTileBeforeItsThumbnail() {
        val file = myFixture.addFileToProject("pics/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.chooseImages = { done -> done(listOf(file)) }
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"pickImages"}""")
        val attachments = posted.filter { it.str("type") == "attachment" }
        assertEquals(2, attachments.size)
        assertEquals("k1", attachments[0].str("id"))
        assertEquals("blue.png", attachments[0].str("name"))
        assertEquals(true, attachments[0].bool("loading"))
        assertNull(attachments[1].bool("loading"))
        assertNotNull(attachments[1].str("thumb"))
    }

    fun testPreparerThatThrowsStillRemovesTheTileAndNotifies() {
        bridge.preparer = { error("boom") }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        assertEquals(true, lastAttachment().bool("failed"))
        assertEquals(0, chat.attachments.size)
        assertTrue(notices().single()!!.contains("Couldn't attach"))
    }

    fun testOutOfMemoryWhilePreparingIsRefusedAsTooLarge() {
        bridge.preparer = { throw OutOfMemoryError("boom") }
        bridge.handle("""{"type":"ready"}""")
        upload("p1", TestImages.BLUE_PNG_BASE64)
        assertEquals(true, lastAttachment().bool("failed"))
        assertTrue(notices().single()!!.contains("too large to process"))
    }

    // --- large histories ---

    fun testBatchesKeepOrderAndStayUnderTheLimit() {
        val items = (1..5).map { buildJsonObject { put("id", "i$it"); put("pad", "xxxxxxxxxx") } } // 30 characters each
        assertEquals(listOf(listOf("i1", "i2"), listOf("i3", "i4"), listOf("i5")), ChatBridge.batches(items, 70).map { b -> b.map { it.str("id") } })
        assertEquals(5, ChatBridge.batches(items, 20).size) // an item over the limit goes alone
        assertEquals(1, ChatBridge.batches(items, 1_000).size)
        assertTrue(ChatBridge.batches(emptyList(), 70).isEmpty())
    }

    fun testEmptyChatStillGetsOneReset() {
        bridge.handle("""{"type":"ready"}""")
        val reset = posted.single { it.str("type") == "reset" }
        assertTrue(reset.arr("items")!!.isEmpty())
        assertTrue(posted.none { it.str("type") == "items" })
    }

    // --- local images in replies ---

    fun testResolveImagesAnswersWithDataUrlsForLocalImagesOnly() {
        val file = myFixture.addFileToProject("docs/blue.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        bridge.handle("""{"type":"resolveImages","reqId":4,"paths":["docs/blue.png","docs/missing.png","https://x/y.png",7]}""")
        val msg = posted.single()
        assertEquals("images", msg.str("type"))
        assertEquals(4L, msg["reqId"].toString().toLong())
        val images = msg.obj("images")!!
        assertEquals(setOf("docs/blue.png"), images.keys)
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", images.str("docs/blue.png"))
    }

    fun testResolveImagesUsesTheChatsFolder() {
        val file = myFixture.addFileToProject(".claude/worktrees/wt1/img/w.png", "").virtualFile
        WriteAction.runAndWait<IOException> { file.setBinaryContent(TestImages.bytes(TestImages.BLUE_PNG_BASE64)) }
        val wtChat = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wtPosted = mutableListOf<JsonObject>()
        val wtBridge = ChatBridge(project, wtChat, {}) { wtPosted += parseJsonObject(it)!! }
        wtBridge.offEdt = { it() }
        wtBridge.onEdt = { it() }
        wtBridge.handle("""{"type":"resolveImages","reqId":1,"paths":["img/w.png"]}""")
        assertNotNull(wtPosted.single().obj("images")!!.str("img/w.png"))
        bridge.handle("""{"type":"resolveImages","reqId":2,"paths":["img/w.png"]}""")
        assertTrue(posted.single().obj("images")!!.isEmpty())
    }

    fun testResolveImagesAnswersTheOthersWhenOnePathThrows() {
        bridge.localImage = { path, _ -> if (path == "bad.png") error("boom") else "data:image/png;base64,AAAA" }
        bridge.handle("""{"type":"resolveImages","reqId":9,"paths":["a.png","bad.png","b.png"]}""")
        assertEquals(setOf("a.png", "b.png"), posted.single().obj("images")!!.keys)
    }

    fun testResolveImagesAnswersEvenWhenEveryPathThrows() {
        bridge.localImage = { _, _ -> throw OutOfMemoryError("boom") }
        bridge.handle("""{"type":"resolveImages","reqId":9,"paths":["a.png"]}""")
        assertTrue(posted.single().obj("images")!!.isEmpty())
    }

    fun testResolveImagesRunsOffTheEdt() {
        val tasks = mutableListOf<() -> Unit>()
        bridge.offEdt = { tasks += it }
        bridge.handle("""{"type":"resolveImages","reqId":1,"paths":["a.png"]}""")
        assertTrue(posted.isEmpty())
        assertEquals(1, tasks.size)
    }

    // --- answering Claude's questions ---

    private fun askTwo() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"ask"}""")
        connections[0].emit(Fx.toolUse("m1", "tq", "AskUserQuestion", Fx.TWO_QUESTIONS), Fx.askQuestion("q1", "tq", Fx.TWO_QUESTIONS))
    }

    private fun answerMsg(requestId: String, answers: String) = """{"type":"answer","requestId":"$requestId","answers":$answers}"""
    private val validAnswers = """{"Which colour?":{"labels":["Red"]},"Which extras?":{"labels":["A, B","C"],"other":"D"}}"""
    private fun responses() = connections[0].sentJson().filter { it.str("type") == "control_response" }

    fun testQuestionItemReachesThePage() {
        askTwo()
        val item = posted.last { it.str("type") == "item" }.obj("item")!!
        assertEquals("awaiting_permission", item.str("status"))
        val q = item.obj("question")!!.arr("questions")!!
        assertEquals(2, q.size)
        assertEquals("Extras", q[1].jsonObject.str("header"))
        assertEquals(true, q[1].jsonObject.bool("multiSelect"))
    }

    fun testValidAnswerIsSentAsAllow() {
        askTwo()
        bridge.handle(answerMsg("q1", validAnswers))
        val resp = responses().single().obj("response")!!.obj("response")!!
        assertEquals("allow", resp.str("behavior"))
        val answers = resp.obj("updatedInput")!!.obj("answers")!!
        assertEquals("Red", answers.str("Which colour?"))
        assertEquals("A, B, C, D", answers.str("Which extras?"))
        assertEquals("running", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testFreeTextForSingleSelectIsAccepted() {
        askTwo()
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":[],"other":"Teal"},"Which extras?":{"labels":["C"]}}"""))
        assertEquals("Teal", responses().single().obj("response")!!.obj("response")!!.obj("updatedInput")!!.obj("answers")!!.str("Which colour?"))
    }

    private fun assertRejected(message: String) {
        askTwo()
        val before = connections[0].sent.size
        bridge.handle(message)
        assertEquals(before, connections[0].sent.size)
        assertEquals("awaiting_permission", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testAnswerForUnknownRequestIsRejected() = assertRejected(answerMsg("zzz", validAnswers))

    fun testAnswerWithWrongQuestionKeysIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red"]},"Something else?":{"labels":["C"]}}"""))
    }

    fun testAnswerMissingAQuestionIsRejected() = assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red"]}}"""))

    fun testAnswerWithLabelNotInOptionsIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Purple"]},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testSingleSelectWithTwoLabelsIsRejected() {
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":["Red","Blue"]},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testOversizeFreeTextIsRejected() {
        val big = "x".repeat(2001)
        assertRejected(answerMsg("q1", """{"Which colour?":{"labels":[],"other":"$big"},"Which extras?":{"labels":["C"]}}"""))
    }

    fun testMalformedAnswerShapesAreRejected() {
        askTwo()
        val before = connections[0].sent.size
        bridge.handle("""{"type":"answer","requestId":"q1"}""")
        bridge.handle(answerMsg("q1", "[]"))
        bridge.handle(answerMsg("q1", """{"Which colour?":"Red","Which extras?":"C"}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":"Red"},"Which extras?":{"labels":["C"]}}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":[1]},"Which extras?":{"labels":["C"]}}"""))
        bridge.handle(answerMsg("q1", """{"Which colour?":{"labels":["Red"],"other":5},"Which extras?":{"labels":["C"]}}"""))
        assertEquals(before, connections[0].sent.size)
    }

    fun testMalformedAnswerShapeReemitsPendingQuestion() {
        askTwo()
        bridge.handle("""{"type":"answer","requestId":"q1","answers":[]}""")
        assertTrue(posted.any { it.obj("item")?.str("kind") == "notice" })
        assertEquals("awaiting_permission", posted.last { it.str("type") == "item" }.obj("item")!!.str("status"))
    }

    fun testAnswerForAnOrdinaryPermissionRequestIsRejected() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"x"}""")
        val input = """{"file_path":"/p/a.txt","content":"hi"}"""
        connections[0].emit(Fx.toolUse("m1", "t1", "Write", input), Fx.canUseTool("r1", "t1", "Write", input))
        bridge.handle(answerMsg("r1", "{}"))
        assertTrue(responses().isEmpty())
    }

    fun testSkipDeniesTheQuestion() {
        askTwo()
        bridge.handle("""{"type":"skipQuestion","requestId":"q1"}""")
        val resp = responses().single().obj("response")!!.obj("response")!!
        assertEquals("deny", resp.str("behavior"))
        assertEquals("The user skipped the question.", resp.str("message"))
    }

    fun testSkipOfAnOrdinaryPermissionRequestIsIgnored() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"x"}""")
        val input = """{"file_path":"/p/a.txt","content":"hi"}"""
        connections[0].emit(Fx.toolUse("m1", "t1", "Write", input), Fx.canUseTool("r1", "t1", "Write", input))
        bridge.handle("""{"type":"skipQuestion","requestId":"r1"}""")
        assertTrue(responses().isEmpty())
    }

    fun testPermissionAllowCannotAnswerAQuestion() {
        askTwo()
        bridge.handle("""{"type":"permission","requestId":"q1","decision":"allow"}""")
        bridge.handle("""{"type":"permission","requestId":"q1","decision":"allowSession"}""")
        assertTrue(responses().isEmpty())
    }
}
