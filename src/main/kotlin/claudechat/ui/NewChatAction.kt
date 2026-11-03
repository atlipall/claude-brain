package claudechat.ui

import claudechat.session.Chats
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The `+` in the Claude Chat title bar. */
class NewChatAction : DumbAwareAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatTabs.find(project)?.open(Chats.getInstance(project).mainSpec())
    }
}
