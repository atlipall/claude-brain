package claudechat.session

import claudechat.testutil.FakeConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatContextRefreshTest {
    private fun chat(onContext: () -> Unit) = Chat(
        id = 1,
        spec = ChatSpec.inProject("/p"),
        history = emptyList(),
        connector = { FakeConnection() },
        initialMode = null,
        editNote = { null },
        editorContext = { onContext(); null },
    )

    @Test fun aDetachedChatDoesNotCaptureContextOnRefresh() {
        var calls = 0
        val chat = chat { calls++ }
        chat.refreshContext()
        assertEquals(0, calls)
        val view = object : SessionView {
            override fun reset(items: List<ChatItem>) {}
            override fun item(item: ChatItem) {}
            override fun state(state: SessionState, sessionId: String?, mode: claudechat.cli.PermissionMode?) {}
            override fun insert(text: String) {}
        }
        chat.attach(view)
        val afterAttach = calls
        chat.detach(view)
        chat.refreshContext()
        assertEquals(afterAttach, calls)
    }

    @Test fun refreshNeverAsksForTheSlowContext() {
        var resolved = 0
        val chat = Chat(
            id = 1, spec = ChatSpec.inProject("/p"), history = emptyList(), connector = { FakeConnection() },
            initialMode = null, editNote = { null },
            resolveContext = { _, done -> resolved++; done(null) },
        )
        chat.attach(object : SessionView {
            override fun reset(items: List<ChatItem>) {}
            override fun item(item: ChatItem) {}
            override fun state(state: SessionState, sessionId: String?, mode: claudechat.cli.PermissionMode?) {}
            override fun insert(text: String) {}
        })
        chat.refreshContext()
        chat.skipContext(true)
        assertEquals(0, resolved)
    }
}
