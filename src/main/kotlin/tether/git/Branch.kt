package tether.git

import java.io.File

private const val BRANCH_REF = "ref: refs/heads/"

/**
 * The branch checked out in [dir]'s repository, read from its HEAD file (no git process, so safe on the EDT); null
 * when detached, outside a repository, or unreadable.
 */
fun currentBranch(dir: String): String? = try {
    val dotGit = generateSequence(File(dir).absoluteFile) { it.parentFile }.map { File(it, ".git") }.firstOrNull { it.exists() }
    dotGit?.let(::gitDir)
        ?.let { File(it, "HEAD").readText().trim() }
        ?.takeIf { it.startsWith(BRANCH_REF) }
        ?.removePrefix(BRANCH_REF)
        ?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

/** A linked worktree's `.git` is a file, `gitdir: <path>` (absolute or relative to the worktree). */
private fun gitDir(dotGit: File): File {
    if (!dotGit.isFile) return dotGit
    val target = File(dotGit.readText().trim().removePrefix("gitdir:").trim())
    return if (target.isAbsolute) target else File(dotGit.parentFile, target.path)
}
