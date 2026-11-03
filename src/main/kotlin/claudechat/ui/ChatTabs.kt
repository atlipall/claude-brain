package claudechat.ui

import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** One closeable tool-window tab per chat. Closing a tab closes its chat (stops its process). EDT only. */
class ChatTabs(private val project: Project, private val toolWindow: ToolWindow) {
    private val chats get() = Chats.getInstance(project)

    fun open(spec: ChatSpec): Chat {
        val registry = chats // captured: the service may be unreachable while the project is closing
        val chat = registry.open(spec)
        val content = ContentFactory.getInstance().createContent(null, chat.title, false)
        content.isCloseable = true
        content.putUserData(CHAT, chat)
        Disposer.register(content) { registry.close(chat) }
        val panel = ChatPanel(project, chat, onNewChat = { open(chats.mainSpec()) }, parent = content)
        content.component = panel.browser.component
        content.preferredFocusableComponent = panel.browser.component
        chat.onTitleChanged = { content.displayName = chat.title } // Chat.send runs on the EDT
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)
        return chat
    }

    fun selectedChat(): Chat? = toolWindow.contentManager.selectedContent?.getUserData(CHAT)

    companion object {
        val CHAT: Key<Chat> = Key.create("claudechat.chat")

        fun find(project: Project): ChatTabs? =
            ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID)?.let { ChatTabs(project, it) }
    }
}
