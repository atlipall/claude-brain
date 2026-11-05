package tether.acp

import tether.image.Image
import tether.image.ImageType
import tether.session.Activity
import tether.session.ChatItem
import tether.session.Choice
import tether.session.PermissionDecision
import tether.session.SessionState
import tether.session.ToolStatus
import tether.testutil.RecordingListener
import tether.testutil.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class FakeAcpClient(private val info: AgentInfo = AgentInfo(loadSession = true, images = true)) : AcpClient {
    class Call<T>(val args: List<Any>, val result: CompletableFuture<T> = CompletableFuture())

    lateinit var listener: AcpListener
    val connected = CompletableFuture<AgentInfo>()
    val opens = mutableListOf<Call<SessionOpened>>()
    val prompts = mutableListOf<Call<String>>()
    val modes = mutableListOf<Call<Unit>>()
    val cancels = mutableListOf<String>()
    var closed = false

    override fun connect(listener: AcpListener): CompletableFuture<AgentInfo> { this.listener = listener; return connected }
    override fun newSession(cwd: String) = Call<SessionOpened>(listOf("new", cwd)).also { opens += it }.result
    override fun loadSession(sessionId: String, cwd: String) = Call<SessionOpened>(listOf("load", sessionId, cwd)).also { opens += it }.result
    override fun prompt(sessionId: String, text: String, images: List<Image>) = Call<String>(listOf(sessionId, text, images)).also { prompts += it }.result
    override fun setMode(sessionId: String, modeId: String) = Call<Unit>(listOf(sessionId, modeId)).also { modes += it }.result
    override fun cancel(sessionId: String) { cancels += sessionId }
    override fun close() { closed = true }

    fun ready(sessionId: String = "s1", modes: List<Choice> = MODES, current: String? = "default") {
        connected.complete(info)
        opens.last().result.complete(SessionOpened(sessionId, modes, current))
    }

    fun update(update: AcpUpdate) = listener.onUpdate("s1", update)

    fun permission(toolId: String, vararg options: PermissionOption): MutableList<String?> {
        val answers = mutableListOf<String?>()
        listener.onPermission(PermissionRequest("s1", AcpUpdate.Tool(toolId, "Run ls", "pending", null, null, null), options.toList()) { answers += it })
        return answers
    }

    companion object {
        val MODES = listOf(Choice("default", "Ask before edits"), Choice("dont_ask", "Don't ask"))
    }
}

class AcpSessionTest {
    private val clients = mutableListOf<FakeAcpClient>()
    private var active: AcpSession? = null
    private val listener = RecordingListener { active?.modeId }
    private val client get() = clients.last()
    private val allow = PermissionOption("allow_once", "Allow once", "allow_once")
    private val deny = PermissionOption("deny", "Deny", "reject_once")

    private fun session(info: AgentInfo = AgentInfo(loadSession = true, images = true), failCreate: Boolean = false, resumeId: String? = null) = AcpSession(
        newClient = {
            if (failCreate) throw IllegalStateException("hermes not found")
            FakeAcpClient(info).also { clients += it }
        },
        listener = listener,
        cwd = "/proj",
        name = "hermes",
        resumeId = resumeId,
    ).also { active = it; it.start() }

    private fun ready(info: AgentInfo = AgentInfo(loadSession = true, images = true)) = session(info).also { client.ready() }

    @Test fun startOpensANewSessionInTheChatFolderAndReportsItsModes() {
        val s = session()

        client.ready()

        assertEquals(listOf<Any>("new", "/proj"), client.opens.single().args)
        assertEquals("s1", s.sessionId)
        assertEquals("default", s.modeId)
        assertEquals(FakeAcpClient.MODES, listener.modes.last())
        assertEquals(Triple(SessionState.IDLE, "s1", null), listener.states.last())
        assertEquals("default", listener.modeIds.last())
    }

    @Test fun messagesSentBeforeTheSessionOpensWaitInTheQueue() {
        val s = session()

        s.send("early")
        val queuedBefore = listener.queuedTexts()
        client.ready()

        assertEquals(listOf("early"), queuedBefore)
        assertEquals(listOf<Any>("s1", "early", emptyList<Image>()), client.prompts.single().args)
        assertTrue(listener.queuedTexts().isEmpty())
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun aTurnStreamsTextAndToolsThenEndsWithAResult() {
        val s = ready()

        s.send("hello", prefix = "Edits: a.txt")
        client.update(AcpUpdate.Thought)
        client.update(AcpUpdate.AgentText("m1", "Hi"))
        client.update(AcpUpdate.AgentText("m1", " there"))
        client.update(AcpUpdate.Tool("t1", "Read a.txt", "pending", null, "/proj/a.txt", null))
        client.update(AcpUpdate.Tool("t1", null, "completed", null, null, "contents"))
        client.prompts.single().result.complete("end_turn")

        assertEquals("Edits: a.txt\n\nhello", client.prompts.single().args[1])
        val items = listener.current()
        assertEquals("hello", (items[0] as ChatItem.UserText).text)
        assertEquals(ChatItem.AssistantText(items[1].id, "Hi there", false), items[1])
        val tool = items[2] as ChatItem.ToolCall
        assertEquals(listOf("Read a.txt", "/proj/a.txt", "contents"), listOf(tool.name, tool.filePath, tool.resultPreview))
        assertEquals(ToolStatus.DONE, tool.status)
        val result = items[3] as ChatItem.TurnResult
        assertFalse(result.isError)
        assertTrue(result.durationMs!! >= 0)
        assertEquals(SessionState.IDLE, s.state)
        assertEquals(
            listOf(Activity.Starting, Activity.Thinking, Activity.Writing, Activity.Running("Read a.txt"), Activity.Thinking, null),
            listener.activities,
        )
    }

    @Test fun permissionShowsTheAgentsOptionsAndSendsTheChosenOne() {
        val s = ready()
        s.send("go")

        val answers = client.permission("perm-check-1", allow, deny)
        val card = listener.latest<ChatItem.ToolCall>()
        val stateWhileAsking = s.state
        s.respond(card.permissionRequestId!!, PermissionDecision.Option("allow_once"))

        assertEquals(ToolStatus.AWAITING_PERMISSION, card.status)
        assertEquals(listOf(Choice("allow_once", "Allow once"), Choice("deny", "Deny")), card.choices)
        assertEquals(SessionState.AWAITING_PERMISSION, stateWhileAsking)
        assertEquals(listOf<String?>("allow_once"), answers)
        assertNull(listener.latest<ChatItem.ToolCall>().permissionRequestId)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun toolCallAskedAboutBeforeItStartedRunsOnceItStarts() {
        val s = ready()
        s.send("go")
        client.permission("t1", allow)
        s.respond(listener.latest<ChatItem.ToolCall>().permissionRequestId!!, PermissionDecision.Option("allow_once"))
        val statusAfterAllow = listener.latest<ChatItem.ToolCall>().status

        client.update(AcpUpdate.Tool("t1", null, "in_progress", null, null, null))

        assertEquals(ToolStatus.DONE, statusAfterAllow)
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(Activity.Running("Run ls"), listener.activities.last())
    }

    @Test fun rejectingOptionMarksTheRowDenied() {
        val s = ready()
        s.send("go")
        client.permission("perm-check-1", allow, deny)

        s.respond(listener.latest<ChatItem.ToolCall>().permissionRequestId!!, PermissionDecision.Option("deny"))

        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun claudeDecisionsAndUnknownOptionsAreNotSent() {
        val s = ready()
        s.send("go")
        val answers = client.permission("perm-check-1", allow, deny)
        val requestId = listener.latest<ChatItem.ToolCall>().permissionRequestId!!

        s.respond(requestId, PermissionDecision.Allow)
        s.respond(requestId, PermissionDecision.Option("allow_always"))

        assertTrue(answers.isEmpty())
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
    }

    @Test fun stopCancelsPermissionsAndTheTurnAndKeepsItOpenUntilTheAgentAnswers() {
        val s = ready()
        s.send("go")
        s.send("later")
        val answers = client.permission("perm-check-1", allow, deny)

        s.interrupt()
        val stateAfterStop = s.state
        client.prompts.single().result.complete("cancelled")

        assertEquals(listOf<String?>(null), answers)
        assertEquals(listOf("s1"), client.cancels)
        assertEquals(SessionState.RUNNING, stateAfterStop)
        assertEquals(listOf("later"), listener.dropped.single().map { it.text })
        assertEquals(ToolStatus.DENIED, listener.current().filterIsInstance<ChatItem.ToolCall>().single().status)
        assertEquals(ChatItem.Notice(listener.latest<ChatItem.Notice>().id, "Interrupted", false), listener.latest<ChatItem.Notice>())
        assertEquals(1, client.prompts.size)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun queuedMessagesGoOutAfterTheTurnEnds() {
        val s = ready()
        s.send("one")

        s.send("two")
        client.prompts[0].result.complete("end_turn")

        assertEquals(listOf("one", "two"), client.prompts.map { it.args[1] })
        assertEquals(listOf("one", "two"), listener.current().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun sendNowCancelsAndSendsTheChosenMessageAfterTheAgentAnswers() {
        val s = ready()
        s.send("one")
        s.send("two")
        s.send("three")
        val three = listener.queues.last().last().id

        s.sendNow(three)
        val promptsBeforeAnswer = client.prompts.size
        client.prompts[0].result.complete("cancelled")

        assertEquals(listOf("s1"), client.cancels)
        assertEquals(1, promptsBeforeAnswer)
        assertEquals(listOf("one", "three"), client.prompts.map { it.args[1] })
        assertEquals(listOf("two"), listener.queuedTexts())
    }

    @Test fun agentErrorEndsTheTurnWithAnErrorResult() {
        val s = ready()
        s.send("go")

        client.prompts.single().result.completeExceptionally(AgentError(-32603, "admitted_turn_failed"))

        val result = listener.latest<ChatItem.TurnResult>()
        assertTrue(result.isError)
        assertEquals("admitted_turn_failed", result.text)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun processExitKillsTheSessionAndReturnsQueuedText() {
        val s = ready()
        s.send("go")
        s.send("queued")

        client.prompts.single().result.completeExceptionally(ConnectionLost("ended"))
        client.listener.onClosed(1, "Traceback: boom")

        assertEquals(SessionState.DEAD, s.state)
        assertEquals("hermes exited (code 1).\nTraceback: boom", listener.latest<ChatItem.Notice>().text)
        assertEquals(listOf("queued"), listener.dropped.single().map { it.text })
        assertNull(listener.activities.last())
        assertFalse(listener.current().any { it is ChatItem.TurnResult })
    }

    @Test fun clientThatCannotStartLeavesTheSessionDead() {
        val s = session(failCreate = true)

        assertEquals(SessionState.DEAD, s.state)
        assertEquals("hermes not found", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun failedSessionOpenClosesTheClientAndShowsTheAgentsError() {
        val s = session()

        client.connected.complete(AgentInfo(loadSession = false, images = false))
        client.opens.single().result.completeExceptionally(AgentError(-32000, "Authentication required"))

        assertEquals(SessionState.DEAD, s.state)
        assertTrue(client.closed)
        assertEquals("Couldn't start hermes: Authentication required", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun restoredHistoryUsesTheHistoryCallbackWhileNewMessagesUseOrdinaryDelivery() {
        val s = session(resumeId = "saved")
        client.connected.complete(AgentInfo(loadSession = true, images = true))
        client.update(AcpUpdate.UserText("u1", "old question"))
        client.update(AcpUpdate.AgentText("a1", "old answer"))
        client.opens.single().result.complete(SessionOpened("saved", FakeAcpClient.MODES, "default"))

        assertEquals(listOf("old question"), listener.historyItems.filterIsInstance<ChatItem.UserText>().map { it.text })
        assertFalse(listener.historyItems.filterIsInstance<ChatItem.AssistantText>().last().streaming)
        assertTrue(listener.liveItems.isEmpty())

        s.send("new question")

        assertEquals(listOf("new question"), listener.liveItems.filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals("new question", client.prompts.single().args[1])
        assertEquals(listOf("old question"), listener.historyItems.filterIsInstance<ChatItem.UserText>().map { it.text })
    }

    @Test fun firstRestoredLoadShowsHistoryButReconnectDoesNotRepeatIt() {
        val s = session(resumeId = "saved")
        client.connected.complete(AgentInfo(loadSession = true, images = true))
        client.update(AcpUpdate.AgentText("old", "saved history"))
        client.opens.single().result.complete(SessionOpened("saved", FakeAcpClient.MODES, "dont_ask"))

        assertEquals(listOf<Any>("load", "saved", "/proj"), client.opens.single().args)
        assertEquals(listOf("saved history"), listener.current().filterIsInstance<ChatItem.AssistantText>().map { it.text })
        assertFalse(listener.latest<ChatItem.AssistantText>().streaming)
        assertEquals("dont_ask", s.modeId)
        assertEquals("dont_ask", listener.modeIds.last())
        client.listener.onClosed(0, "")

        s.restart()
        client.connected.complete(AgentInfo(loadSession = true, images = true))
        client.update(AcpUpdate.AgentText("old", "saved history"))
        client.opens.single().result.complete(SessionOpened("saved", FakeAcpClient.MODES, "default"))

        assertEquals(listOf<Any>("load", "saved", "/proj"), client.opens.single().args)
        assertEquals(listOf("saved history"), listener.current().filterIsInstance<ChatItem.AssistantText>().map { it.text })
        assertEquals("default", s.modeId)
        assertEquals("default", listener.modeIds.last())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun restartLoadsTheSameSessionAndDropsTheReplay() {
        val s = ready()
        client.listener.onClosed(0, "")
        val shownBefore = listener.current().size

        s.restart()
        client.connected.complete(AgentInfo(loadSession = true, images = true))
        client.update(AcpUpdate.AgentText("old", "replayed history"))
        client.opens.single().result.complete(SessionOpened("s1", FakeAcpClient.MODES, "default"))

        assertEquals(2, clients.size)
        assertEquals(listOf<Any>("load", "s1", "/proj"), client.opens.single().args)
        assertFalse(listener.current().any { it is ChatItem.AssistantText })
        assertEquals(shownBefore + 1, listener.current().size)
        assertEquals("Reconnected to the session.", listener.latest<ChatItem.Notice>().text)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun disposeDuringATurnCancelsAndClosesWhenTheTurnEnds() {
        val s = ready()
        s.send("go")
        val answers = client.permission("perm-check-1", allow)

        s.dispose()
        val closedAtOnce = client.closed
        client.prompts.single().result.complete("cancelled")

        assertEquals(listOf<String?>(null), answers)
        assertEquals(listOf("s1"), client.cancels)
        assertFalse(closedAtOnce)
        assertTrue(client.closed)
    }

    @Test fun disposeWhileIdleClosesAtOnce() {
        val s = ready()

        s.dispose()

        assertTrue(client.cancels.isEmpty())
        assertTrue(client.closed)
    }

    @Test fun setModeChangesTheModeOnceTheAgentAgrees() {
        val s = ready()

        s.setMode("plan")
        s.setMode("dont_ask")
        val modeBeforeReply = s.modeId
        client.modes.single().result.complete(Unit)

        assertEquals(listOf<Any>("s1", "dont_ask"), client.modes.single().args)
        assertEquals("default", modeBeforeReply)
        assertEquals("dont_ask", s.modeId)
        assertEquals("dont_ask", listener.modeIds.last())
    }

    @Test fun refusedModeChangeShowsTheReasonAndKeepsTheMode() {
        val s = ready()

        s.setMode("dont_ask")
        client.modes.single().result.completeExceptionally(AgentError(-32602, "Mode changes apply between turns"))

        assertEquals("default", s.modeId)
        assertEquals("Couldn't change the mode: Mode changes apply between turns", listener.latest<ChatItem.Notice>().text)
        assertEquals("default", listener.modeIds.last())
    }

    @Test fun agentModeUpdateIsReported() {
        val s = ready()

        client.update(AcpUpdate.Mode("dont_ask"))

        assertEquals("dont_ask", s.modeId)
        assertEquals("dont_ask", listener.modeIds.last())
    }

    @Test fun imagesAreDroppedWithANoticeWhenTheAgentTakesNone() {
        val s = ready(AgentInfo(loadSession = false, images = false))
        val red = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

        s.send("look", images = listOf(red), thumbs = listOf(""))

        assertEquals(emptyList<Image>(), client.prompts.single().args[2])
        assertEquals("hermes does not accept images; 1 image was not sent.", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun usageUpdateFillsTheContextMeter() {
        ready()

        client.update(AcpUpdate.Usage(50_000, 200_000))

        val usage = listener.contexts.single()
        assertEquals(listOf(50_000L, 200_000L, 25L), listOf(usage.used, usage.max, usage.percent.toLong()))
    }
}
