package tether.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The `+` in the Tether title bar: opens a new chat in the project, with the agent last selected. */
class NewChatAction : DumbAwareAction("New Chat", "Start a new chat in this project", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatTabs.find(project)?.newChat()
    }
}

/** Opens a chat in another checkout (a new or existing worktree), or resumes a past session, from a popup. */
class WorkspaceChatAction : DumbAwareAction("Chat in Workspace…", "Start a chat in a worktree, or resume a past session", AllIcons.Vcs.Branch) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tabs = ChatTabs.find(project) ?: return
        val anchor = e.inputEvent?.component
        tabs.chooseWorkspace { popup ->
            if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
        }
    }
}
