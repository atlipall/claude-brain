package claudechat.session

import claudechat.git.canonicalPath

/**
 * How a chat starts. [cwd] is the launch directory. For a new worktree it is the project and [worktree]
 * names the worktree claude creates (the chat then moves to the folder system/init reports). [title] is the name the
 * user gave a resumed session, if any.
 */
data class ChatSpec(
    val label: String,
    val cwd: String,
    val worktree: String? = null,
    val resumeId: String? = null,
    val title: String? = null,
) {
    companion object {
        const val MAIN = "main"

        fun inProject(projectDir: String) = ChatSpec(MAIN, projectDir)

        fun newWorktree(projectDir: String, name: String) = ChatSpec(name, projectDir, worktree = name)

        fun existingWorktree(path: String) = ChatSpec(ChatNames.lastSegment(path), path)

        fun resume(projectDir: String, session: SessionSummary) =
            ChatSpec(ChatNames.folderLabel(session.folder, projectDir), session.folder, resumeId = session.id, title = session.customTitle)
    }
}

object ChatNames {
    /** A chat's name until the user renames it: [label], or `label 2`, `label 3`… when [taken] has it. */
    fun defaultName(label: String, taken: Collection<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) label else "$label $it" }.first { it !in taken }

    /** How other Claude apps mark an archived session: its custom title starts with this. */
    const val ARCHIVED = "[archived]"

    /**
     * A name as typed or stored, whitespace collapsed and without the [ARCHIVED] mark (a name must not archive its
     * session); null when blank (the chat goes back to its default name).
     */
    fun clean(name: String?): String? =
        name?.replace(Regex("\\s+"), " ")?.trim()?.removePrefix(ARCHIVED)?.trim()?.takeIf { it.isNotEmpty() }

    /** `main` for the project folder, otherwise the folder's name (a worktree name). */
    fun folderLabel(folder: String, projectDir: String): String =
        if (canonicalPath(folder) == canonicalPath(projectDir)) ChatSpec.MAIN else lastSegment(folder)

    fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
