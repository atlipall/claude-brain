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
    /** [worktrees] is `git worktree list` (null without git, which hides the worktree rows). */
    fun build(projectDir: String, worktrees: List<Worktree>?): List<NewChatChoice> = buildList {
        add(NewChatChoice.Open("In this project", ChatSpec.inProject(projectDir)))
        if (worktrees != null) {
            add(NewChatChoice.NewWorktree)
            val project = canonicalPath(projectDir)
            for (wt in worktrees.filterNot { it.isMain || canonicalPath(it.path) == project }) {
                val branch = wt.branch?.let { " ($it)" }.orEmpty()
                add(NewChatChoice.Open("In worktree ${wt.name}$branch", ChatSpec.existingWorktree(wt.path)))
            }
        }
        add(NewChatChoice.Resume)
    }

    fun resumeText(s: SessionSummary, projectDir: String, time: (Long) -> String): String =
        "${s.title} · ${ChatNames.folderLabel(s.folder, projectDir)} · ${time(s.modifiedMillis)}"
}
