package claudechat.ui

import claudechat.session.Chat
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.ui.content.Content

/** An action in a Tether tab's context menu, on that tab's chat. */
abstract class ChatTabAction : ToolWindowContextMenuActionBase() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        e.presentation.isEnabledAndVisible = toolWindow.id == ChatToolWindowFactory.ID && content?.getUserData(ChatTabs.CHAT) != null
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val chat = content?.getUserData(ChatTabs.CHAT) ?: return
        perform(toolWindow, content, chat)
    }

    protected abstract fun perform(toolWindow: ToolWindow, content: Content, chat: Chat)
}

/** "Rename Chat…". A blank name restores the default (`main`, a worktree name). */
class RenameChatAction : ChatTabAction() {
    override fun perform(toolWindow: ToolWindow, content: Content, chat: Chat) {
        val name = Messages.showInputDialog(
            toolWindow.project, "Name (leave empty for the default):", "Rename Chat", null, chat.title, null,
        ) ?: return
        chat.rename(name)
    }
}

/** "Archive Chat": hides the session from the resume list (it stays under Archived sessions) and closes the tab. */
class ArchiveChatAction : ChatTabAction() {
    override fun perform(toolWindow: ToolWindow, content: Content, chat: Chat) {
        chat.archive() // a chat with no session yet has nothing to keep: it just closes
        toolWindow.contentManager.removeContent(content, true) // closes the chat
    }
}
