package claudechat.session

import claudechat.git.canonicalPath

/**
 * How a chat starts. [cwd] is the launch directory. For a new worktree it is the project and [worktree]
 * names the worktree claude creates (the chat then moves to the folder system/init reports).
 */
data class ChatSpec(
    val label: String,
    val cwd: String,
    val worktree: String? = null,
    val resumeId: String? = null,
) {
    companion object {
        const val MAIN = "main"

        fun inProject(projectDir: String) = ChatSpec(MAIN, projectDir)

        fun newWorktree(projectDir: String, name: String) = ChatSpec(name, projectDir, worktree = name)

        fun existingWorktree(path: String) = ChatSpec(ChatNames.lastSegment(path), path)

        fun resume(projectDir: String, session: SessionSummary) =
            ChatSpec(ChatNames.folderLabel(session.folder, projectDir), session.folder, resumeId = session.id)
    }
}

/** What a chat runs: Claude, or the ACP agent started from the command line [acp]. */
data class Agent(val id: String, val name: String, val acp: List<String>? = null) {
    companion object {
        val CLAUDE = Agent("claude", "Claude")

        /** Named after its executable: `/opt/bin/hermes acp` is Hermes. */
        fun acp(command: List<String>) = Agent("acp", command.first().substringAfterLast('/').replaceFirstChar(Char::uppercase), command)
    }
}

object ChatNames {
    private const val TITLE_CHARS = 30

    /** `label` alone, or `label · <first ~30 characters of the first message>`. */
    fun tabTitle(label: String, firstMessage: String?): String {
        val text = firstMessage?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) return label
        val short = if (text.length > TITLE_CHARS) text.take(TITLE_CHARS).trimEnd() + "…" else text
        return "$label · $short"
    }

    /** `main` for the project folder, otherwise the folder's name (a worktree name). */
    fun folderLabel(folder: String, projectDir: String): String =
        if (canonicalPath(folder) == canonicalPath(projectDir)) ChatSpec.MAIN else lastSegment(folder)

    fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
