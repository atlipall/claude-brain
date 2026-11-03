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

    private fun write(name: String, text: String, dir: com.intellij.openapi.vfs.VirtualFile = root) = WriteAction.run<Exception> {
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, text)
    }

    /** Sends [text] and ends its turn, once its checkpoint is in (it is put in the background). */
    private fun turn(chat: claudechat.session.Chat, text: String): ChatItem.UserText {
        chat.send(text)
        return endTurn(chat)
    }

    /** Ends the turn of the message just sent, once its checkpoint is in (it is put in the background). */
    private fun endTurn(chat: claudechat.session.Chat): ChatItem.UserText {
        val item = sent(chat).last()
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked",
            { TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, item.id) }, 10)
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return sent(chat).last()
    }

    /** The chat's messages as the page would get them. */
    private fun sent(chat: claudechat.session.Chat): List<ChatItem.UserText> {
        var shown = emptyList<ChatItem>()
        chat.attach(object : claudechat.session.SessionView {
            override fun reset(items: List<ChatItem>) { shown = items }
            override fun item(item: ChatItem) {}
            override fun state(state: claudechat.session.SessionState, sessionId: String?, mode: claudechat.cli.PermissionMode?) {}
            override fun insert(text: String) { inserted += text }
        })
        return shown.filterIsInstance<ChatItem.UserText>()
    }
    private val inserted = mutableListOf<String>()

    private var asked: String? = null

    /** Answers the revert dialog with button [index] (see RevertChoice order; Cancel is last). */
    private fun answer(index: Int) = TestDialogManager.setTestDialog { message -> asked = message; index }

    private fun read(name: String) = root.findChild(name)?.let { VfsUtil.loadText(it) }

    fun testRevertRestoresTheFilesAsTheyWereBeforeTheMessage() {
        write("a.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("a.txt", "after")
        write("new.txt", "created by Claude")
        answer(1) // Files Only
        chat.revertTo(item.id)
        assertEquals("before", read("a.txt"))
        assertNull("files created since are removed", read("new.txt"))
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("reverted"))
    }

    /**
     * A label put while a change set is open (a command or VFS refresh, in any open project) joins it, and Local
     * History's revert skips change sets that change nothing in the reverted folder, label and all.
     */
    fun testAMessageSentWhileAnotherFolderChangesCanStillBeRevertedTo() {
        write("d.txt", "before")
        val elsewhere = VfsUtil.findFile(Files.createTempDirectory("elsewhere"), true)!!
        PsiTestUtil.addContentRoot(module, elsewhere)
        val chat = chats.open(ChatSpec.inProject(root.path))
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, {
            write("x.txt", "typed meanwhile", elsewhere)
            chat.send("change it")
            // Time for a label put straight from the pooled thread to land inside this command's change set.
            Thread.sleep(500)
        }, "Typing", null)
        val item = endTurn(chat)
        write("d.txt", "after")
        answer(1) // Files Only
        chat.revertTo(item.id)
        assertEquals("before", read("d.txt"))
    }

    fun testARunningTurnIsNotReverted() {
        write("c.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        chat.send("change it")
        val id = sent(chat).single().id
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked",
            { TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, id) }, 10)
        write("c.txt", "after")
        answer(1)
        chat.revertTo(id)
        assertEquals("after", read("c.txt"))
    }

    fun testCancellingTheQuestionChangesNothing() {
        write("b.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        val item = turn(chat, "change it")
        write("b.txt", "after")
        answer(3) // Cancel
        chat.revertTo(item.id)
        assertEquals("after", read("b.txt"))
        assertNull(TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }

    fun testRevertingFilesAndConversationDropsTheMessageAndRefillsTheInputBox() {
        write("f.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        conn.emit(Fx.init("sess-1", cwd = root.path))
        chat.send("first")
        conn.emit(Fx.assistantText("m1", "ok", uuid = "a-1"))
        endTurn(chat)
        val second = turn(chat, "second")
        write("f.txt", "after")
        val old = conn
        answer(0) // Files and Conversation
        chat.revertTo(second.id)
        assertEquals("before", read("f.txt"))
        assertTrue(old.stopped)
        assertEquals(listOf("first"), sent(chat).map { it.text })
        assertEquals(listOf("second"), inserted)
        assertNull("files and memory agree again", TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }

    fun testRevertingOnlyTheConversationLeavesTheFiles() {
        write("g.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        conn.emit(Fx.init("sess-1", cwd = root.path))
        val item = turn(chat, "change it")
        write("g.txt", "after")
        answer(2) // Conversation Only
        chat.revertTo(item.id)
        assertEquals("after", read("g.txt"))
        assertTrue(sent(chat).isEmpty())
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("may still"))
    }

    fun testAnUntrackedCheckoutOffersOnlyTheConversation() {
        val outside = VfsUtil.findFile(Files.createTempDirectory("untracked"), true)!! // not a content root
        val chat = chats.open(ChatSpec.inProject(outside.path))
        conn.emit(Fx.init("sess-1", cwd = outside.path))
        chat.send("change it")
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val item = sent(chat).single()
        assertTrue("the link shows without a checkpoint", item.revertable)
        answer(0) // the only choice: Conversation Only
        chat.revertTo(item.id)
        assertTrue(asked!!.contains("can't be reverted"))
        assertTrue(sent(chat).isEmpty())
    }
}
