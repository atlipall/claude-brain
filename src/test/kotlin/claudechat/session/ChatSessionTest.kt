package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.CliMessages
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.cli.obj
import claudechat.cli.str
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import claudechat.testutil.RecordingListener
import claudechat.testutil.TestImages
import claudechat.image.Image
import claudechat.image.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private val listener = RecordingListener()
    private val conn get() = connections.last()

    private fun session(
        resumeId: String? = null,
        mode: PermissionMode? = null,
        failConnect: Boolean = false,
        cwd: String = "/proj",
        worktree: String? = null,
    ) =
        ChatSession(
            connect = { launch ->
                if (failConnect) throw IllegalStateException("claude not found")
                launches += launch
                FakeConnection().also { connections += it }
            },
            listener = listener,
            cwd = cwd,
            resumeId = resumeId,
            initialMode = mode,
            worktree = worktree,
        ).also { it.start() }

    private val writeInput = """{"file_path":"/p/a.txt","content":"hi"}"""

    // --- lifecycle and turns ---

    @Test fun startSendsInitializeAndIsIdle() {
        session()
        assertEquals("initialize", conn.sentJson().single().obj("request")!!.str("subtype"))
        assertEquals(SessionState.IDLE, listener.lastState())
    }

    @Test fun initResponseAndSystemInitSetModeAndSessionId() {
        val s = session()
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize"), "plan"))
        assertEquals(PermissionMode.PLAN, s.mode)
        conn.emit(Fx.init("sess-9", "acceptEdits"))
        assertEquals("sess-9", s.sessionId)
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun sendShowsUserTextAndSendsPrefixedPayload() {
        val s = session()
        s.send("hello", prefix = "[IDE] note")
        assertEquals("hello", listener.latest<ChatItem.UserText>().text)
        assertEquals(listOf("[IDE] note\n\nhello"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
    }

    private val red = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

    @Test fun sendWithImagesSendsAContentArrayAndShowsThumbnails() {
        val s = session()
        s.send("what is this?", prefix = "[IDE] note", images = listOf(red), thumbs = listOf("data:image/png;base64,T"))
        val item = listener.latest<ChatItem.UserText>()
        assertEquals("what is this?", item.text)
        assertEquals(listOf("data:image/png;base64,T"), item.images)
        assertEquals(listOf("[IDE] note\n\nwhat is this?"), conn.userMessages())
        assertEquals(listOf(listOf(TestImages.RED_PNG_BASE64)), conn.userImages())
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun imageOnlyMessageWithoutNotesHasNoTextBlock() {
        val s = session()
        s.send("", images = listOf(red, red), thumbs = listOf("", ""))
        val content = conn.sentJson().last().obj("message")!!["content"].toString()
        assertEquals(2, Regex("\"type\":\"image\"").findAll(content).count())
        assertFalse(content.contains("\"type\":\"text\""))
    }

    @Test fun queuedMessageKeepsItsImages() {
        val s = session()
        s.send("first")
        s.send("second", images = listOf(red))
        assertEquals(listOf("first"), conn.userMessages())
        conn.emit(Fx.result())
        assertEquals(listOf("first", "second"), conn.userMessages())
        assertEquals(listOf(emptyList(), listOf(TestImages.RED_PNG_BASE64)), conn.userImages())
    }

    @Test fun noticeIsShownInTheChat() {
        val s = session()
        s.notice("Couldn't attach a.bmp: not a PNG, JPEG, GIF or WebP image.")
        val notice = listener.latest<ChatItem.Notice>()
        assertEquals("Couldn't attach a.bmp: not a PNG, JPEG, GIF or WebP image.", notice.text)
        assertTrue(notice.isError)
    }

    @Test fun streamedTextIsOneItemAndFinalAssistantMessageIsNotDuplicated() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.messageStart("m1"), Fx.textDelta("m1", 1, "DO"), Fx.textDelta("m1", 1, "NE"))
        val streaming = listener.latest<ChatItem.AssistantText>()
        assertEquals("DONE", streaming.text)
        assertTrue(streaming.streaming)
        conn.emit(Fx.blockStop("m1", 1), Fx.assistantText("m1", "DONE"))
        val texts = listener.current().filterIsInstance<ChatItem.AssistantText>()
        assertEquals(1, texts.size)
        assertFalse(texts.single().streaming)
    }

    @Test fun assistantTextWithoutStreamingCreatesItem() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.assistantText("m2", "plain"))
        assertEquals(ChatItem.AssistantText(listener.latest<ChatItem.AssistantText>().id, "plain", false), listener.latest<ChatItem.AssistantText>())
    }

    @Test fun resultEndsTurnAndFlushesQueue() {
        val s = session()
        s.send("one")
        s.send("two")
        assertEquals(listOf("one"), conn.userMessages())
        conn.emit(Fx.result())
        assertEquals(listOf("one", "two"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.result())
        assertEquals(SessionState.IDLE, s.state)
        val result = listener.latest<ChatItem.TurnResult>()
        assertFalse(result.isError)
        assertEquals(5566L, result.durationMs)
    }

    @Test fun errorResultCarriesText() {
        val s = session()
        s.send("x")
        conn.emit(Fx.errorResult())
        val r = listener.latest<ChatItem.TurnResult>()
        assertTrue(r.isError)
        assertEquals("[ede_diagnostic] result_type=user", r.text)
    }

    // --- tools and permissions ---

    @Test fun permissionRequestMarksToolAndState() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, tool.status)
        assertEquals("r1", tool.permissionRequestId)
        assertEquals("/p/a.txt", tool.filePath)
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
    }

    @Test fun allowSendsAllowWithOriginalInput() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Allow)
        val response = conn.sentJson().last().obj("response")!!
        assertEquals("r1", response.str("request_id"))
        assertEquals("allow", response.obj("response")!!.str("behavior"))
        assertEquals("hi", response.obj("response")!!.obj("updatedInput")!!.str("content"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun denySendsDenyAndToolEndsDenied() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Deny("use b.txt"))
        assertEquals(CliMessages.deny("r1", "use b.txt"), conn.sent.last())
        conn.emit(Fx.toolResult("t1", "use b.txt", isError = true))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun allowForSessionAutoAllowsLaterRequestsForSameTool() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.AllowForSession)
        conn.emit(Fx.toolUse("m2", "t2", "Write", writeInput), Fx.canUseTool("r2", "t2", "Write", writeInput))
        assertEquals("r2", conn.sentJson().last().obj("response")!!.str("request_id"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun editResultEnablesDiff() {
        val s = session()
        s.send("x")
        conn.emit(
            Fx.toolUse("m1", "t1", "Edit", """{"file_path":"/p/f.txt","old_string":"two","new_string":"2"}"""),
            Fx.toolResult("t1", "ok", toolUseResultJson = Fx.editResult("/p/f.txt", "line two\n")),
        )
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, tool.status)
        assertTrue(tool.hasDiff)
        assertEquals("ok", tool.resultPreview)
        assertEquals(FileDiff("/p/f.txt", "line two\n"), s.diffFor("t1"))
    }

    @Test fun createdFileDiffsAgainstEmpty() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult("/p/a.txt")))
        assertEquals(FileDiff("/p/a.txt", ""), s.diffFor("t1"))
    }

    @Test fun readResultHasNoDiff() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/f.txt"}"""), Fx.toolResult("t1", "contents"))
        assertFalse(listener.latest<ChatItem.ToolCall>().hasDiff)
        assertNull(s.diffFor("t1"))
    }

    @Test fun readOfAnImageShowsItInTheToolRow() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/blue.png"}"""), Fx.readImageResult("t1", TestImages.BLUE_PNG_BASE64))
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, tool.status)
        assertEquals(listOf("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}"), tool.images)
        assertEquals("", tool.resultPreview)
        assertFalse(tool.hasDiff)
    }

    @Test fun atMostSixImagesOfOneToolResultAreShown() {
        val s = session()
        s.send("x")
        val block = """{"type":"image","source":{"type":"base64","data":"${TestImages.BLUE_PNG_BASE64}","media_type":"image/png"}}"""
        val line = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":[${List(10) { block }.joinToString(",")}]}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u12"}"""
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/blue.png"}"""), line)
        val images = listener.latest<ChatItem.ToolCall>().images
        assertEquals(10, images.size)
        assertEquals(6, images.count { it.startsWith("data:image/png;base64,") })
        assertEquals(List(4) { "" }, images.drop(6))
    }

    @Test fun anImageBlockThatIsNotAnImageShowsNothing() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/x.png"}"""), Fx.readImageResult("t1", "AAAA"), Fx.readImageResult("t1", "%%%"))
        assertEquals(listOf(""), listener.latest<ChatItem.ToolCall>().images)
    }

    // --- control ---

    @Test fun interruptSendsControlRequestAndShowsNotice() {
        val s = session()
        s.send("long task")
        s.send("queued")
        s.interrupt()
        assertEquals("interrupt", conn.sentJson().last().obj("request")!!.str("subtype"))
        conn.emit(Fx.interruptedUser(), Fx.errorResult())
        assertEquals("Interrupted", listener.latest<ChatItem.Notice>().text)
        assertTrue(listener.items.none { it is ChatItem.TurnResult })
        assertEquals(listOf("long task"), conn.userMessages())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun interruptDeniesPendingPermission() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.interrupt()
        val sent = conn.sentJson()
        assertEquals("deny", sent[sent.size - 2].obj("response")!!.obj("response")!!.str("behavior"))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun setPermissionModeSendsRequest() {
        val s = session()
        s.setPermissionMode(PermissionMode.ACCEPT_EDITS)
        val req = conn.sentJson().last().obj("request")!!
        assertEquals("set_permission_mode", req.str("subtype"))
        assertEquals("acceptEdits", req.str("mode"))
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun failedControlRequestShowsNotice() {
        session()
        conn.emit(Fx.errorResponse("x", "unsupported"))
        assertTrue(listener.latest<ChatItem.Notice>().text.contains("unsupported"))
    }

    @Test fun processExitMarksDeadAndRestartResumesWithMode() {
        val s = session(mode = PermissionMode.PLAN)
        s.send("x")
        conn.emit(Fx.init("sess-7", "plan"))
        conn.exit(1, "boom")
        assertEquals(SessionState.DEAD, s.state)
        val notice = listener.latest<ChatItem.Notice>()
        assertTrue(notice.isError)
        assertTrue(notice.text.contains("boom"))
        s.send("ignored while dead")
        assertEquals(listOf("x"), connections.first().userMessages())

        s.restart()
        assertEquals(SessionLaunch("sess-7", PermissionMode.PLAN, "/proj"), launches.last())
        assertEquals(2, connections.size)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun linesFromDisposedConnectionAreIgnored() {
        val s = session()
        val old = conn
        s.dispose()
        assertTrue(old.stopped)
        val before = listener.items.size
        old.emit(Fx.assistantText("m1", "late"))
        assertEquals(before, listener.items.size)
    }

    @Test fun connectFailureIsReportedAsDead() {
        session(failConnect = true)
        assertEquals(SessionState.DEAD, listener.lastState())
        assertEquals("claude not found", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun resumeIdAndModeArePassedToFirstLaunch() {
        session(resumeId = "abc", mode = PermissionMode.AUTO)
        assertEquals(SessionLaunch("abc", PermissionMode.AUTO, "/proj"), launches.single())
    }

    private class ThrowingSendConnection : CliConnection {
        var stopped = false
        var onExit: ((Int, String) -> Unit)? = null
        override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) { this.onExit = onExit }
        override fun send(line: String) { throw IllegalStateException("pipe closed") }
        override fun stop() { stopped = true }
    }

    @Test fun startFailureAfterConnectStopsConnectionAndIgnoresLateExit() {
        val bad = ThrowingSendConnection()
        val s = ChatSession(connect = { bad }, listener = listener, cwd = "/proj")
        s.start()
        assertEquals(SessionState.DEAD, s.state)
        assertEquals(1, listener.items.filterIsInstance<ChatItem.Notice>().size)
        assertTrue(bad.stopped)
        bad.onExit!!(1, "late")
        assertEquals(1, listener.items.filterIsInstance<ChatItem.Notice>().size)
    }

    // --- folder ---

    @Test fun firstLaunchUsesLaunchDirAndWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        assertEquals(SessionLaunch(null, null, "/proj", "wt-1"), launches.single())
        assertEquals("/proj", s.cwd)
    }

    @Test fun systemInitCwdReplacesLaunchDirAndRestartDropsWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        conn.emit(Fx.init("sess-3", "default", cwd = "/proj/.claude/worktrees/wt-1"))
        assertEquals("/proj/.claude/worktrees/wt-1", s.cwd)
        conn.exit(1, "")
        s.restart()
        assertEquals(
            SessionLaunch("sess-3", PermissionMode.DEFAULT, "/proj/.claude/worktrees/wt-1", null),
            launches.last())
    }

    @Test fun blankInitCwdKeepsLaunchDir() {
        val s = session(cwd = "/proj")
        conn.emit("""{"type":"system","subtype":"init","cwd":"","session_id":"s1","permissionMode":"default"}""")
        assertEquals("/proj", s.cwd)
    }
}
