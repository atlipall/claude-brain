package claudechat.cli

enum class PermissionMode(val cli: String) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    AUTO("auto");

    companion object {
        fun fromCli(value: String?): PermissionMode? = entries.firstOrNull { it.cli == value }
    }
}

/** What a new CLI process should start as. */
data class SessionLaunch(val resumeId: String?, val permissionMode: PermissionMode?)

object ClaudeCommand {
    fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> = buildList {
        add(binary)
        addAll(listOf(
            "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
        ))
        launch.permissionMode?.let { add("--permission-mode"); add(it.cli) }
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
