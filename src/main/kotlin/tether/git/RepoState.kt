package tether.git

import tether.context.ContextNotes
import java.io.File

/** A git operation that stops part way and waits for the user, recognised by the file git keeps while it is underway. */
enum class GitOperation(val description: String, val marker: String) {
    REBASE("a rebase", "rebase-merge"),
    APPLY("a rebase or `git am`", "rebase-apply"),
    MERGE("a merge", "MERGE_HEAD"),
    CHERRY_PICK("a cherry-pick", "CHERRY_PICK_HEAD"),
    REVERT("a revert", "REVERT_HEAD"),
    BISECT("a bisect", "BISECT_LOG");

    /** [description] starting a sentence: "A merge". */
    val sentenceStart: String get() = description.replaceFirstChar(Char::uppercase)
}

/**
 * A checkout's git state at one moment: enough to tell what the user did with git between two moments (commit, push,
 * pull, switch branch, reset, stash, start or finish a merge or rebase).
 */
data class RepoState(
    /** The checked-out branch; null when HEAD is detached. */
    val branch: String?,
    /** The commit HEAD points at; null before the first commit. */
    val head: String?,
    /** The branch's upstream, such as `origin/main`; null when it has none. */
    val upstream: String?,
    /** The commit [upstream] points at, as of the last fetch or push. */
    val upstreamHead: String?,
    val stashes: Int,
    /** The newest stash; tells a stash swapped for another apart from no change when [stashes] stays the same. */
    val stashTop: String?,
    val operation: GitOperation?,
)

/** Reads [RepoState]s and describes how two differ. Every method runs git: never call on the EDT. */
class RepoStates(private val git: GitRunner = ::quickGitRunner) {
    /**
     * [dir]'s state, or null outside a repository (or when git is missing). Two git processes in the usual case (a
     * branch with commits and an upstream); a few more otherwise.
     */
    fun read(dir: String): RepoState? {
        val folder = File(dir)
        if (!folder.isDirectory) return null
        val refs = readRefs(folder) ?: return null
        val stashes = run(folder, "rev-list", "--walk-reflogs", "refs/stash")?.lines().orEmpty()
        return RepoState(
            branch = refs.branch,
            head = refs.head,
            upstream = refs.upstream,
            upstreamHead = refs.upstreamHead,
            stashes = stashes.size,
            stashTop = stashes.firstOrNull(),
            operation = GitOperation.entries.firstOrNull { File(refs.gitDir, it.marker).exists() },
        )
    }

    private class Refs(val gitDir: String, val branch: String?, val head: String?, val upstream: String?, val upstreamHead: String?)

    /**
     * Asks for everything in one `rev-parse`, which fails as a whole when there is no upstream, no commit yet or HEAD
     * is detached; then asks for each part on its own.
     */
    private fun readRefs(dir: File): Refs? {
        val all = run(dir, "rev-parse", "--absolute-git-dir", "HEAD", "@{upstream}", "--abbrev-ref", "@{upstream}", "--symbolic-full-name", "HEAD")
            ?.lines()
        if (all != null && all.size == 5) return Refs(all[0], branch = all[4], head = all[1], upstream = all[3], upstreamHead = all[2])
        val gitDir = run(dir, "rev-parse", "--absolute-git-dir") ?: return null
        val upstream = run(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
        return Refs(
            gitDir,
            branch = run(dir, "symbolic-ref", "--short", "-q", "HEAD"),
            head = git.commit(dir, "HEAD"),
            upstream = upstream,
            upstreamHead = upstream?.let { git.commit(dir, "@{upstream}") },
        )
    }

    /**
     * The note telling Claude what changed in [dir]'s git state from [before] to [after], or null when nothing it
     * should know about did.
     */
    fun describe(dir: String, before: RepoState, after: RepoState): String? {
        val folder = File(dir)
        val changes = listOfNotNull(
            headChange(folder, before, after),
            upstreamChange(folder, before, after),
            stashChange(before, after),
            operationChange(before.operation, after.operation),
        )
        if (changes.isEmpty()) return null
        return "[IDE] Since your last turn, git changed in this checkout (done by the user or another tool, not by you):\n" +
            changes.joinToString("\n") { "- $it" } +
            "\nCheck the files and `git status` before relying on what you remember of them."
    }

    private fun headChange(dir: File, before: RepoState, after: RepoState): String? {
        if (before.branch != after.branch) {
            val now = after.head?.let { " HEAD is now at ${describeCommit(dir, it)}." }.orEmpty()
            return "Switched from ${place(before)} to ${place(after)}.$now"
        }
        val old = before.head
        val new = after.head ?: return null
        if (old == new) return null
        val on = after.branch?.let(::code) ?: "the detached HEAD"
        if (old == null) return "First commits on $on: ${commitList(dir, new, count(dir, new))}."
        val (dropped, added) = divergence(dir, old, new)
        return when {
            dropped == 0 -> "${plural(added, "new commit")} on $on: ${commitList(dir, "$old..$new", added)}."
            added == 0 -> "$on was moved back ${plural(dropped, "commit")} (reset) to ${describeCommit(dir, new)}; " +
                "no longer on it: ${commitList(dir, "$new..$old", dropped)}."
            else -> "$on was rewritten (amend, rebase or reset), replacing ${plural(dropped, "earlier commit")}; " +
                "its commits since they diverged: ${commitList(dir, "$old..$new", added)}."
        }
    }

    /** Pushes, fetches and pulls, seen as the upstream moving. Skipped when the branch changed: its upstream is another. */
    private fun upstreamChange(dir: File, before: RepoState, after: RepoState): String? {
        if (before.branch != after.branch) return null
        val branch = after.branch?.let(::code) ?: "HEAD"
        val upstream = after.upstream ?: return before.upstream?.let { "$branch no longer tracks ${code(it)}." }
        val remoteHead = after.upstreamHead ?: return null
        if (before.upstream != upstream) {
            val pushed = if (remoteHead == after.head) " and was pushed to it" else ""
            return "$branch now tracks ${code(upstream)}$pushed."
        }
        if (before.upstreamHead == remoteHead) return null
        val (ahead, behind) = after.head?.let { divergence(dir, it, remoteHead) } ?: (0 to 0)
        if (wasPushed(dir, before, remoteHead)) {
            val unpushed = if (ahead > 0) " (${plural(ahead, "commit")} still unpushed)" else ""
            return "Pushed to ${code(upstream)}, now at ${describeCommit(dir, remoteHead)}$unpushed."
        }
        return "${code(upstream)} moved to ${describeCommit(dir, remoteHead)} (fetched or pulled); " +
            "$branch is ${plural(ahead, "commit")} ahead and $behind behind it."
    }

    /**
     * Whether the upstream last moved because of a push: its reflog says so (`update by push`). Without a reflog, a
     * push is assumed when the upstream now points at a commit the branch already had.
     */
    private fun wasPushed(dir: File, before: RepoState, remoteHead: String): Boolean {
        val reason = run(dir, "log", "-g", "-1", "--format=%gs", "@{upstream}")
        if (reason != null) return reason.startsWith(PUSH_REFLOG)
        val oldHead = before.head ?: return false
        return git(dir, listOf("merge-base", "--is-ancestor", remoteHead, oldHead)) != null
    }

    private fun stashChange(before: RepoState, after: RepoState): String? {
        val now = plural(after.stashes, "stash", "stashes")
        return when {
            after.stashes > before.stashes -> "Stashed changes ($now now)."
            after.stashes < before.stashes -> "Popped or dropped a stash ($now left)."
            after.stashTop != before.stashTop -> "Stashed and popped or dropped stashes ($now now)."
            else -> null
        }
    }

    private fun operationChange(before: GitOperation?, after: GitOperation?): String? = when {
        before == after -> null
        before == null && after != null -> "${after.sentenceStart} is in progress (there may be conflicts)."
        before != null && after == null -> "${before.sentenceStart} finished or was aborted."
        before != null && after != null -> "${before.sentenceStart} ended and ${after.description} is in progress."
        else -> null
    }

    /** How many commits only [left] has and how many only [right] has, from one `rev-list --left-right --count`. */
    private fun divergence(dir: File, left: String, right: String): Pair<Int, Int> {
        val counts = run(dir, "rev-list", "--left-right", "--count", "$left...$right")?.split(Regex("\\s+"))
        return (counts?.getOrNull(0)?.toIntOrNull() ?: 0) to (counts?.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun place(state: RepoState): String =
        state.branch?.let { "branch ${code(it)}" } ?: state.head?.let { "a detached HEAD at `${it.take(SHORT_SHA)}`" } ?: "no commit"

    /** `abc1234` “subject”. */
    private fun describeCommit(dir: File, commit: String): String =
        run(dir, "log", "-1", "--format=%h %s", commit)?.let(::formatLogLine) ?: "`${commit.take(SHORT_SHA)}`"

    /** The newest [MAX_LISTED] commits of [range] (which holds [total]), newest first, then how many more there are. */
    private fun commitList(dir: File, range: String, total: Int): String {
        val shown = run(dir, "log", "--format=%h %s", "-n", "$MAX_LISTED", range)?.lines().orEmpty()
            .joinToString("; ", transform = ::formatLogLine)
        return if (total > MAX_LISTED) "$shown; and ${total - MAX_LISTED} more" else shown
    }

    private fun formatLogLine(line: String): String {
        val sha = line.substringBefore(' ')
        val subject = ContextNotes.clean(line.substringAfter(' ', ""), MAX_SUBJECT)
        return if (subject.isEmpty()) "`$sha`" else "`$sha` “$subject”"
    }

    /** [name] (a branch or remote) as a code span, with anything that could break it out cleaned away. */
    private fun code(name: String) = "`${ContextNotes.clean(name, MAX_SUBJECT)}`"

    private fun count(dir: File, range: String): Int = run(dir, "rev-list", "--count", range)?.toIntOrNull() ?: 0

    private fun run(dir: File, vararg args: String): String? = git.output(dir, *args)

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private companion object {
        const val MAX_LISTED = 10
        const val MAX_SUBJECT = 80
        const val SHORT_SHA = 7
        const val PUSH_REFLOG = "update by push"
    }
}
