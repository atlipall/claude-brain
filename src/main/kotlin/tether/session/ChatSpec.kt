package tether.session

import tether.cli.PermissionMode
import tether.git.canonicalPath
import tether.git.currentBranch

/**
 * How a chat starts. [cwd] is the launch directory. For a new worktree it is the project and [worktree]
 * names the worktree claude creates (the chat then moves to the folder system/init reports). [title] is the name the
 * user gave a resumed session, if any. [setup] is what the chat was left with when it was last open.
 */
data class ChatSpec(
    val label: String,
    val cwd: String,
    val worktree: String? = null,
    val resumeId: String? = null,
    val title: String? = null,
    val setup: SessionSetup = SessionSetup(),
    /** The provider of a restored session; null lets a new draft use the last selected agent. */
    val agentId: String? = null,
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

/** A chat's permission mode, model and effort as the user left them; null leaves each to the defaults. */
data class SessionSetup(val mode: PermissionMode? = null, val model: String? = null, val effort: String? = null)

/** What a chat runs: Claude, or the ACP agent started from [acp]. */
data class Agent(val id: String, val name: String, val acp: List<String>? = null) {
    companion object {
        val CLAUDE = Agent("claude", "Claude")

        /** Names the configured agent after its executable, such as `hermes acp`. */
        fun acp(command: List<String>) = Agent("acp", command.first().substringAfterLast('/').replaceFirstChar(Char::uppercase), command)
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

    /** A project-folder chat ([ChatSpec.MAIN]) is named after the checked-out branch, if any; others keep [ChatSpec.label]. */
    fun branchLabel(spec: ChatSpec, branch: (String) -> String? = ::currentBranch): String =
        if (spec.label == ChatSpec.MAIN && spec.worktree == null) branch(spec.cwd) ?: ChatSpec.MAIN else spec.label

    fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
