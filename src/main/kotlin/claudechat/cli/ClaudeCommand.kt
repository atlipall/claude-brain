package claudechat.cli

enum class PermissionMode(val cli: String, val label: String) {
    DEFAULT("default", "Ask before edits"),
    ACCEPT_EDITS("acceptEdits", "Accept edits"),
    PLAN("plan", "Plan only"),
    AUTO("auto", "Auto");

    companion object {
        fun fromCli(value: String?): PermissionMode? = entries.firstOrNull { it.cli == value }
    }
}

/**
 * What a new CLI process should start as. [cwd] is the process directory; [worktree] asks claude to
 * create `<cwd>/.claude/worktrees/<name>` on branch `worktree-<name>` and run there.
 */
data class SessionLaunch(
    val resumeId: String?,
    val permissionMode: PermissionMode?,
    val cwd: String,
    val worktree: String? = null,
)

object ClaudeCommand {
    fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> = buildList {
        add(binary)
        addAll(listOf(
            "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
            "--prompt-suggestions", "true",
        ))
        launch.permissionMode?.let { add("--permission-mode"); add(it.cli) }
        launch.worktree?.let { add("--worktree"); add(it) }
        launch.resumeId?.let { add("--resume"); add(it) }
        addAll(extraArgs)
    }
}

object ClaudeBinary {
    /** Configured path if set (null if not executable); otherwise PATH entries, then common install locations. */
    fun resolve(configured: String, pathEnv: String?, home: String, isExecutable: (String) -> Boolean): String? {
        if (configured.isNotBlank()) return configured.trim().takeIf(isExecutable)
        val fromPath = pathEnv.orEmpty().split(':').filter { it.isNotBlank() }.map { "${it.trimEnd('/')}/claude" }
        val fallbacks = listOf("$home/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude")
        return (fromPath + fallbacks).firstOrNull(isExecutable)
    }
}
