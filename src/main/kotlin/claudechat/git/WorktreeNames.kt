package claudechat.git

/** Names for `claude --worktree <name>` (folder `.claude/worktrees/<name>`, branch `worktree-<name>`). */
object WorktreeNames {
    private val VALID = Regex("[A-Za-z0-9._-]+")

    /** Why [name] cannot be used, or null when it can. */
    fun error(name: String, existing: Set<String>): String? = when {
        name.isEmpty() -> "Enter a name."
        !VALID.matches(name) -> "Use only letters, digits, '.', '_' and '-'."
        // claude would read a leading '-' as an option.
        name.startsWith("-") -> "The name cannot start with '-'."
        // git refuses these in the branch name worktree-<name> (and '.'/'..' are not folders we can create).
        name.contains("..") || name.endsWith(".") || name.endsWith(".lock") -> "Git cannot use this name for a branch."
        // "main" names the project's own checkout, so no worktree may take it (any case).
        name.equals("main", ignoreCase = true) -> "\"main\" is reserved for the project itself."
        name in existing -> "A worktree named '$name' already exists."
        else -> null
    }

    fun suggest(existing: Set<String>): String =
        generateSequence(1) { it + 1 }.map { "wt-$it" }.first { it !in existing }
}
