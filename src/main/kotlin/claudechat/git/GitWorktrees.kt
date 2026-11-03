package claudechat.git

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One entry of `git worktree list --porcelain`. [branch] is the short branch name, null when detached. */
data class Worktree(val path: String, val branch: String?, val isMain: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

object WorktreePorcelain {
    /** The first block is the main worktree. Bare entries are dropped (nothing can run there). */
    fun parse(output: String): List<Worktree> =
        output.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { block -> block.lines().filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull { index, lines ->
                val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ")
                    ?: return@mapIndexedNotNull null
                if ("bare" in lines) return@mapIndexedNotNull null
                val branch = lines.firstOrNull { it.startsWith("branch ") }
                    ?.removePrefix("branch ")?.removePrefix("refs/heads/")
                Worktree(path, branch, isMain = index == 0)
            }
}

/**
 * The project's worktrees. [lister] runs `git worktree list --porcelain` in a directory and returns its
 * stdout, or null when git is missing or the directory is not in a repository (tests inject a fake).
 */
class GitWorktrees(
    private val projectDir: String,
    private val lister: (File) -> String? = ::runGitWorktreeList,
) {
    /** Every worktree including the main one; null without git. Runs a process: never call on the EDT. */
    fun list(): List<Worktree>? = lister(File(projectDir))?.let(WorktreePorcelain::parse)

    /** Folders claude --worktree created under `<project>/.claude/worktrees`, sorted. */
    fun claudeWorktreeDirs(): List<String> =
        File(projectDir, ".claude/worktrees").listFiles { f -> f.isDirectory }.orEmpty().map { it.path }.sorted()

    /** Folders whose past sessions can be resumed: the project, every git worktree, every dir under `.claude/worktrees`. */
    fun knownFolders(worktrees: List<Worktree>?): List<String> =
        (listOf(projectDir) + worktrees.orEmpty().map { it.path } + claudeWorktreeDirs()).distinct()

    /** Names a new worktree must not reuse. */
    fun existingNames(worktrees: List<Worktree>?): Set<String> =
        (worktrees.orEmpty().filterNot { it.isMain }.map { it.name } + claudeWorktreeDirs().map { it.substringAfterLast('/') }).toSet()
}

/** Runs `git worktree list --porcelain` in [dir]; null if git is missing, fails, or takes longer than 5 seconds. */
fun runGitWorktreeList(dir: File): String? = try {
    val process = ProcessBuilder("git", "worktree", "list", "--porcelain")
        .directory(dir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.outputStream.close()
    val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    when {
        !process.waitFor(5, TimeUnit.SECONDS) -> { process.destroyForcibly(); null }
        process.exitValue() != 0 -> null
        else -> out
    }
} catch (_: IOException) {
    null
}
