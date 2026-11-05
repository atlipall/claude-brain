package tether.git

import java.io.File

/** One changed file, by its path relative to the repository root ([oldPath] for a rename). */
data class FileChange(val path: String, val kind: Kind, val oldPath: String? = null) {
    enum class Kind { ADDED, MODIFIED, DELETED, RENAMED }
}

/**
 * What differs between a [DiffBase] commit and the files on disk: committed, staged and unstaged changes, and new
 * files git does not track yet (ignored files are left out). Every method runs git: never call on the EDT.
 */
class CheckoutChanges(
    private val git: GitRunner = ::gitRunner,
    private val show: (File, String) -> ByteArray? = { dir, spec -> runGitBytes(dir, "show", spec, timeoutSeconds = 30) },
) {
    /** The changes, or null when git failed. */
    fun list(base: DiffBase): List<FileChange>? {
        val root = File(base.root)
        val diff = git(root, listOf("diff", "--name-status", "-z", "-M", base.commit, "--")) ?: return null
        val untracked = git(root, listOf("ls-files", "--others", "--exclude-standard", "-z")) ?: return null
        return parse(diff, untracked)
    }

    /** The file's bytes at the base commit, or null when it was not there. */
    fun baseContent(base: DiffBase, path: String): ByteArray? = show(File(base.root), "${base.commit}:$path")

    companion object {
        /**
         * Parses `git diff --name-status -z -M` and `git ls-files --others -z` output. A copy (C) counts as added;
         * M, T (type change) and U (unmerged) as modified.
         */
        fun parse(nameStatus: String, untracked: String): List<FileChange> {
            val out = LinkedHashMap<String, FileChange>()
            val fields = nameStatus.split('\u0000').iterator()
            while (fields.hasNext()) {
                val status = fields.next()
                if (status.isEmpty() || !fields.hasNext()) continue
                val path = fields.next()
                val change = when (status[0]) {
                    'A' -> FileChange(path, FileChange.Kind.ADDED)
                    'D' -> FileChange(path, FileChange.Kind.DELETED)
                    'R' -> if (fields.hasNext()) FileChange(fields.next(), FileChange.Kind.RENAMED, oldPath = path) else continue
                    'C' -> if (fields.hasNext()) FileChange(fields.next(), FileChange.Kind.ADDED) else continue
                    else -> FileChange(path, FileChange.Kind.MODIFIED)
                }
                out[change.path] = change
            }
            untracked.split('\u0000').filter { it.isNotEmpty() }.forEach { out.putIfAbsent(it, FileChange(it, FileChange.Kind.ADDED)) }
            return out.values.toList()
        }
    }
}
