package claudechat.ui

import claudechat.git.Worktree
import claudechat.git.canonicalPath
import claudechat.session.ChatNames
import claudechat.session.ChatSpec
import claudechat.session.SessionSummary

/** One row of the New-chat popup. */
sealed interface NewChatChoice {
    val text: String

    data class Open(override val text: String, val spec: ChatSpec) : NewChatChoice

    data object NewWorktree : NewChatChoice {
        override val text = "In a new worktree…"
    }

    data object Resume : NewChatChoice {
        override val text = "Resume a past session…"
    }
}

object NewChatChoices {
    /**
     * [worktrees] is `git worktree list` (null without git, which hides the worktree rows). [acp] is the ACP agent's
     * command line: it adds rows for the project and the existing worktrees with that agent.
     */
    fun build(projectDir: String, worktrees: List<Worktree>?, acp: List<String>? = null): List<NewChatChoice> = buildList {
        val project = canonicalPath(projectDir)
        val others = worktrees.orEmpty().filterNot { it.isMain || canonicalPath(it.path) == project }
        fun inWorktree(wt: Worktree) = "In worktree ${wt.name}" + wt.branch?.let { " ($it)" }.orEmpty()
        add(NewChatChoice.Open("In this project", ChatSpec.inProject(projectDir)))
        if (worktrees != null) {
            add(NewChatChoice.NewWorktree)
            for (wt in others) add(NewChatChoice.Open(inWorktree(wt), ChatSpec.existingWorktree(wt.path)))
        }
        if (acp != null) {
            val with = " with ${ChatSpec.agentName(acp)}"
            add(NewChatChoice.Open("In this project$with", ChatSpec.inProject(projectDir).withAcp(acp)))
            for (wt in others) add(NewChatChoice.Open(inWorktree(wt) + with, ChatSpec.existingWorktree(wt.path).withAcp(acp)))
        }
        add(NewChatChoice.Resume)
    }

    fun resumeText(s: SessionSummary, projectDir: String, isOpen: Boolean = false, time: (Long) -> String): String =
        "${s.title} · ${ChatNames.folderLabel(s.folder, projectDir)} · ${time(s.modifiedMillis)}" + if (isOpen) " · open" else ""

    /** The already-open chat running [sessionId], if any; resuming it a second time would write to the same transcript. */
    fun <T> openChatFor(sessionId: String, openChats: List<Pair<String?, T>>): T? =
        openChats.firstOrNull { it.first == sessionId }?.second
}
