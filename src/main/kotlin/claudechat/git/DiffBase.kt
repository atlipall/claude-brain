package claudechat.git

import java.io.File

/**
 * The commit a checkout's changes are shown against. [root] is the repository's top level, [since] names the base for
 * people ("main", "the session start", "HEAD").
 */
data class DiffBase(val root: String, val commit: String, val since: String) {
    /** `main (0123456)`. */
    val label: String get() = "$since (${commit.take(7)})"
    val title: String get() = "Changes since $label"
}

/** Picks what a chat's changes are compared with. Every method runs git: never call on the EDT. */
class DiffBases(private val git: GitRunner = ::gitRunner) {
    /** The commit HEAD points at in [dir], or null (no repository, no commits). */
    fun head(dir: String): String? = commit(File(dir), "HEAD")

    /**
     * On a branch other than main (or master): where it forked from main. Otherwise (on main, or detached): the
     * session start: [startCommit] if it still exists, else the last commit before [startedAtMillis], else HEAD.
     * Null outside a repository or before the first commit.
     */
    fun resolve(dir: String, startCommit: String?, startedAtMillis: Long?): DiffBase? {
        val root = run(File(dir), "rev-parse", "--show-toplevel") ?: return null
        val top = File(root)
        val head = commit(top, "HEAD") ?: return null
        val branch = run(top, "symbolic-ref", "--short", "-q", "HEAD")
        val main = MAIN_NAMES.firstOrNull { run(top, "rev-parse", "--verify", "-q", "refs/heads/$it") != null }
        if (branch != null && main != null && branch != main) {
            run(top, "merge-base", "refs/heads/$main", "HEAD")?.let { return DiffBase(root, it, main) }
        }
        val start = startCommit?.let { commit(top, it) }
            ?: startedAtMillis?.let { run(top, "rev-list", "-1", "--before=@${it / 1000}", "HEAD") }
        return if (start != null) DiffBase(root, start, SESSION_START) else DiffBase(root, head, "HEAD")
    }

    private fun commit(dir: File, rev: String) = run(dir, "rev-parse", "--verify", "-q", "$rev^{commit}")

    /** Trimmed stdout, or null when git failed or printed nothing. */
    private fun run(dir: File, vararg args: String): String? = git(dir, args.toList())?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val MAIN_NAMES = listOf("main", "master")
        const val SESSION_START = "the session start"
    }
}
