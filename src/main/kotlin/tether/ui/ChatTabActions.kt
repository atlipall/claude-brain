package tether.ui

import tether.session.Chat
import tether.session.Chats
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
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

/**
 * "Archive Chat": hides the session from the resume list (it stays under Archived sessions) and closes the tab, which
 * closes the chat. A chat with no session yet has nothing to keep: it just closes.
 */
class ArchiveChatAction : ChatTabAction() {
    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        super.update(e, toolWindow, content)
        if (content?.getUserData(ChatTabs.CHAT)?.agent?.acp != null) e.presentation.isEnabledAndVisible = false
    }

    override fun perform(toolWindow: ToolWindow, content: Content, chat: Chat) {
        chat.archive()
        toolWindow.contentManager.removeContent(content, true)
    }
}

/**
 * "Archive All Past Sessions…": moves every past session of the project and its worktrees to Archived sessions,
 * keeping their names, after asking. Sessions open in a tab are left alone. Listing and writing the transcripts run
 * in the background; the count is reported in a notification.
 */
class ArchiveAllSessionsAction : ToolWindowContextMenuActionBase() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        e.presentation.isEnabledAndVisible = toolWindow.id == ChatToolWindowFactory.ID
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val project = toolWindow.project
        val chats = Chats.getInstance(project)
        background(project, chats::archivable, failed = "Couldn't list the past sessions") { sessions ->
            if (sessions.isEmpty()) {
                notify(project, "No past sessions to archive", NotificationType.INFORMATION)
                return@background
            }
            if (!confirmed(project, sessions.size)) return@background
            background(project, { chats.archiveAll(sessions) }, failed = "Couldn't archive the past sessions") { archived ->
                reportArchived(project, archived, sessions.size)
            }
        }
    }

    private fun confirmed(project: Project, count: Int): Boolean = Messages.showOkCancelDialog(
        project,
        "Move ${sessionCount(count)} of this project and its worktrees to Archived sessions? " +
            "Open chats are left alone, and resuming an archived session unarchives it.",
        "Archive All Past Sessions",
        "Archive",
        Messages.getCancelButton(),
        Messages.getQuestionIcon(),
    ) == Messages.OK

    private fun sessionCount(count: Int) = if (count == 1) "1 past session" else "$count past sessions"

    /**
     * Says how many of the [confirmed] sessions were [archived]: a warning when fewer were, which happens when a
     * transcript couldn't be written (logged) or a session was opened in a tab after the user confirmed.
     */
    private fun reportArchived(project: Project, archived: Int, confirmed: Int) {
        if (archived == confirmed) {
            notify(project, "Archived ${sessionCount(archived)}", NotificationType.INFORMATION)
        } else {
            notify(project, "Archived $archived of ${sessionCount(confirmed)}", NotificationType.WARNING,
                content = "The rest are open in a tab or couldn't be written (see the IDE log).")
        }
    }
}
