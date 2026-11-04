package claudechat.ui

import claudechat.git.Worktree
import claudechat.git.WorktreeNames
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.SessionSummary
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.text.DateFormatUtil

/** The New-chat popups. Their contents are computed off the EDT (git, transcript files); [place] shows them. */
object NewChatPopup {
    fun show(project: Project, tabs: ChatTabs, place: (JBPopup) -> Unit) {
        val chats = Chats.getInstance(project)
        background(project, { chats.worktrees.list() }) { worktrees ->
            val choices = NewChatChoices.build(chats.projectDir, worktrees)
            place(listPopup(project, "New Chat", choices, NewChatChoice::text) { choice ->
                chosen(project, tabs, choice, worktrees, place)
            })
        }
    }

    private fun chosen(project: Project, tabs: ChatTabs, choice: NewChatChoice, worktrees: List<Worktree>?, place: (JBPopup) -> Unit) {
        val chats = Chats.getInstance(project)
        when (choice) {
            is NewChatChoice.Open -> tabs.open(choice.spec)
            NewChatChoice.NewWorktree ->
                background(project, { chats.worktrees.existingNames(worktrees) }) { existing ->
                    askWorktreeName(project, existing)?.let { tabs.open(ChatSpec.newWorktree(chats.projectDir, it)) }
                }
            NewChatChoice.Resume -> showResume(project, tabs, worktrees, place, archived = false)
            NewChatChoice.Archived -> showResume(project, tabs, worktrees, place, archived = true)
        }
    }

    /** The past sessions, or with [archived] the archived ones (resuming one unarchives it). */
    private fun showResume(project: Project, tabs: ChatTabs, worktrees: List<Worktree>?, place: (JBPopup) -> Unit, archived: Boolean) {
        val chats = Chats.getInstance(project)
        background(project, { chats.store.listAll(chats.worktrees.knownFolders(worktrees), archived = archived) }) { sessions ->
            if (sessions.isEmpty()) {
                val what = if (archived) "archived sessions" else "past sessions"
                place(JBPopupFactory.getInstance().createMessage("No $what in this project or its worktrees."))
            } else {
                // The list is built on the EDT, so the open chats are current when it is shown.
                val openChat = { s: SessionSummary -> NewChatChoices.openChatFor(s.id, openSessions(chats)) }
                val text = { s: SessionSummary ->
                    NewChatChoices.resumeText(s, chats.projectDir, isOpen = openChat(s) != null, DateFormatUtil::formatPrettyDateTime)
                }
                val title = if (archived) "Resume an Archived Session" else "Resume a Past Session"
                place(listPopup(project, title, sessions, text) { s ->
                    // A session already live in a tab is never resumed twice: two claude processes would write one transcript.
                    val existing = openChat(s)
                    if (existing != null && tabs.select(existing)) return@listPopup
                    // The transcript is read in the background: a long session would hold up the IDE.
                    val spec = ChatSpec.resume(chats.projectDir, s)
                    background(project, { if (archived) chats.unarchive(s); chats.read(spec) }) { tabs.open(spec, it) }
                })
            }
        }
    }

    private fun openSessions(chats: Chats): List<Pair<String?, Chat>> = chats.all().map { it.session.sessionId to it }

    private fun askWorktreeName(project: Project, existing: Set<String>): String? = Messages.showInputDialog(
        project,
        "Name for the new worktree (folder .claude/worktrees/<name>, branch worktree-<name>):",
        "New Worktree",
        null,
        WorktreeNames.suggest(existing),
        object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = WorktreeNames.error(inputString.trim(), existing)
            override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null
            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        },
    )?.trim()

    /** A speed-searchable list; [onChosen] runs on the EDT after the popup has closed. */
    private fun <T> listPopup(project: Project, title: String, items: List<T>, text: (T) -> String, onChosen: (T) -> Unit): JBPopup =
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create<T>("") { text(it) })
            .setNamerForFiltering { text(it) }
            .setItemChosenCallback { item -> ApplicationManager.getApplication().invokeLater({ onChosen(item) }, project.disposed) }
            .createPopup()
}
