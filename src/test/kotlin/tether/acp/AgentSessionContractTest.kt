package tether.acp

import tether.cli.PermissionMode
import tether.cli.obj
import tether.cli.str
import tether.session.AgentSession
import tether.session.ChatItem
import tether.session.ChatListener
import tether.session.ChatSession
import tether.session.PermissionDecision
import tether.session.SessionSetup
import tether.session.SessionState
import tether.testutil.FakeConnection
import tether.testutil.Fx
import tether.testutil.RecordingListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionContractTest {
    @Test fun acpLeavesNativeOnlyOperationsUnsupported() {
        val client = FakeAcpClient()
        val listener = RecordingListener()
        val session: AgentSession = AcpSession({ client }, listener, "/proj", "hermes")
        session.start()
        client.ready()

        session.setModel("other-model")
        session.setEffort("high")
        session.answerFailed("unknown")

        assertEquals(SessionSetup(), session.setup)
        assertEquals("/proj", session.cwd)
        assertTrue(session.cwdReported)
        assertFalse(session.canRewindTo("unknown"))
        assertFalse(session.rewind("unknown"))
        assertTrue(session.turnDiffs("unknown").isEmpty())
        assertNull(session.diffFor("unknown"))
        assertNull(session.pendingQuestions("unknown"))
        assertFalse(session.answer("unknown", emptyMap()))
        assertTrue(client.prompts.isEmpty())
        assertTrue(client.modes.isEmpty())
        assertTrue(listener.items.isEmpty())
    }

    @Test fun nativeGenericModeAndSetupRetainTypedClaudeChoices() {
        val connection = FakeConnection()
        val native = ChatSession({ connection }, RecordingListener(), "/proj", setup = SessionSetup(PermissionMode.PLAN, "chosen-model", "medium"))
        val session: AgentSession = native
        session.start()

        session.setMode(PermissionMode.ACCEPT_EDITS.cli)
        val sentBeforeUnknownMode = connection.sent.toList()
        session.setMode("an-acp-only-mode")

        assertEquals(PermissionMode.ACCEPT_EDITS, native.mode)
        assertEquals(PermissionMode.ACCEPT_EDITS.cli, session.modeId)
        assertEquals(SessionSetup(PermissionMode.ACCEPT_EDITS, "chosen-model", "medium"), session.setup)
        assertEquals(sentBeforeUnknownMode, connection.sent)
        assertFalse(session.cwdReported)
        connection.emit(Fx.init(cwd = "/actual/worktree"))
        assertTrue(session.cwdReported)
        assertEquals("/actual/worktree", session.cwd)
    }

    @Test fun acpOptionDoesNotSettleANativePermission() {
        val connection = FakeConnection()
        val listener = RecordingListener()
        val session: AgentSession = ChatSession({ connection }, listener, "/proj")
        session.start()
        session.send("go")
        connection.emit(Fx.canUseTool("r1", "t1", "Read", "{}"))
        val sentBeforeOption = connection.sent.toList()

        session.respond("r1", PermissionDecision.Option("allow_once"))

        assertEquals(sentBeforeOption, connection.sent)
        assertEquals(SessionState.AWAITING_PERMISSION, session.state)
        assertEquals("r1", listener.latest<ChatItem.ToolCall>().permissionRequestId)

        session.respond("r1", PermissionDecision.Allow)

        assertEquals("allow", connection.sentJson().last().obj("response")!!.obj("response")!!.str("behavior"))
        assertEquals(SessionState.RUNNING, session.state)
        assertNull(listener.latest<ChatItem.ToolCall>().permissionRequestId)
    }

    @Test fun historyDeliveryDefaultsToOrdinaryItemDelivery() {
        val received = mutableListOf<ChatItem>()
        val listener = object : ChatListener {
            override fun onItem(item: ChatItem) { received += item }
            override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {}
        }
        val item = ChatItem.UserText("old", "previous question")

        listener.onHistoryItem(item)

        assertEquals(listOf(item), received)
    }
}
