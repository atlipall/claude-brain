package claudechat.ide

import claudechat.session.ChatItem
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.SessionStore
import claudechat.testutil.FakeConnection
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import claudechat.testutil.Fx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.nio.file.Files

/** Reverting a chat's checkout to a message's Local History label, on real files. */
class TurnCheckpointsTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val chats get() = Chats.getInstance(project)
    private lateinit var conn: FakeConnection
    private val root get() = myFixture.tempDirFixture.getFile("")!!

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addContentRoot(module, root)
        chats.closeAll()
        chats.connector = { FakeConnection().also { conn = it } }
        chats.store = SessionStore(Files.createTempDirectory("claude-home").toFile())
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun write(name: String, text: String) = WriteAction.run<Exception> {
        val file = root.findChild(name) ?: root.createChildData(this, name)
        VfsUtil.saveText(file, text)
    }

    /** Sends [text] and ends its turn, once its checkpoint is in (it is put in the background). */
    private fun turn(chat: claudechat.session.Chat, text: String): ChatItem.UserText {
        chat.send(text)
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked", { sent(chat).revertable }, 10)
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return sent(chat)
    }

    /** The chat's one message as the page would get it. */
    private fun sent(chat: claudechat.session.Chat): ChatItem.UserText {
        var shown = emptyList<ChatItem>()
        chat.attach(object : claudechat.session.SessionView {
            override fun reset(items: List<ChatItem>) { shown = items }
            override fun item(item: ChatItem) {}
            override fun state(state: claudechat.session.SessionState, sessionId: String?, mode: String?) {}
            override fun insert(text: String) {}
        })
        return shown.filterIsInstance<ChatItem.UserText>().single()
    }

    private fun read(name: String) = root.findChild(name)?.let { VfsUtil.loadText(it) }

    fun testRevertRestoresTheFilesAsTheyWereBeforeTheMessage() {
        write("a.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("a.txt", "after")
        write("new.txt", "created by Claude")
        TestDialogManager.setTestDialog(TestDialog.YES)
        chat.revertTo(item.id)
        assertEquals("before", read("a.txt"))
        assertNull("files created since are removed", read("new.txt"))
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("reverted"))
    }

    fun testARunningTurnIsNotReverted() {
        write("c.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.send("change it")
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked", { sent(chat).revertable }, 10)
        write("c.txt", "after")
        TestDialogManager.setTestDialog(TestDialog.YES)
        chat.revertTo(sent(chat).id)
        assertEquals("after", read("c.txt"))
    }

    fun testCancellingTheQuestionChangesNothing() {
        write("b.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("b.txt", "after")
        TestDialogManager.setTestDialog(TestDialog.NO)
        chat.revertTo(item.id)
        assertEquals("after", read("b.txt"))
        assertNull(TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }
}
