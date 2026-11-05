package tether.git

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Runs git in [dir] with extra [env] and [input] on stdin, for at most [timeoutSeconds]; stdout, or null when git fails. */
typealias GitEnvRunner = (dir: File, args: List<String>, env: Map<String, String>, input: ByteArray?, timeoutSeconds: Long) -> ByteArray?

/** A git tree object holding a repository's files as they were on disk; [root] is the repository's top level. */
data class TreeSnapshot(val root: String, val tree: String)

/**
 * Snapshots of a checkout's files, to tell what a command changed on disk. A snapshot is a tree of every file git
 * does not ignore, tracked or not, written through a private index per repository: the real index, HEAD and the stash
 * are left alone. Every method runs git: never call on the EDT. [close] deletes the private indexes.
 */
class WorkingTrees(
    private val git: GitEnvRunner = { dir, args, env, input, timeout ->
        runGitBytes(dir, *args.toTypedArray(), timeoutSeconds = timeout, env = env, input = input)
    },
) : AutoCloseable {
    private class Repo(val root: String, val realIndex: File)

    /** The repository of each folder asked about. */
    private val repos = ConcurrentHashMap<String, Repo>()
    /**
     * Each repository's private index, seeded once from the real one. Kept between snapshots so its stat data spares
     * git from hashing unchanged files again, untracked ones included.
     */
    private val indexes = ConcurrentHashMap<String, File>()
    /** Repositories where a snapshot failed or ran out of time; they are not tried again, so commands are not held up each time. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The files of [dir]'s repository as on disk now, or null outside a repository or when git fails or is too slow. */
    fun snapshot(dir: String): TreeSnapshot? {
        val deadline = Deadline(SNAPSHOT_SECONDS)
        val repo = repo(dir, deadline) ?: return null
        if (repo.root in failed) return null
        val index = try {
            indexes.computeIfAbsent(repo.root) { seed(repo.realIndex) }
        } catch (_: IOException) {
            failed += repo.root
            return null
        }
        val tree = writeTree(File(repo.root), deadline, index)
        if (tree == null) failed += repo.root
        return tree?.let { TreeSnapshot(repo.root, it) }
    }

    /** Adds every file under [top] to the private [index] and writes its tree id; one at a time per index, as git locks it while adding. */
    private fun writeTree(top: File, deadline: Deadline, index: File): String? {
        val env = mapOf("GIT_INDEX_FILE" to index.absolutePath)
        return synchronized(index) {
            call(top, deadline, listOf("add", "--all", "--", "."), env)?.let { call(top, deadline, listOf("write-tree"), env) }
        }?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The text files that differ from [before] to [after], by absolute path, each with its content in [before]: null
     * for a file [after] added. Paths in [known] are skipped, as are binary files, files over [MAX_FILE_BYTES],
     * symlinks and submodules. Null when git fails or is too slow, or the snapshots are of different repositories.
     */
    fun changes(before: TreeSnapshot, after: TreeSnapshot, known: Set<String> = emptySet()): Map<String, String?>? {
        if (before.root != after.root) return null
        if (before.tree == after.tree) return emptyMap()
        val deadline = Deadline(CHANGES_SECONDS)
        val top = File(before.root)
        val raw = call(top, deadline, listOf("diff-tree", "-r", "-z", "--no-renames", "--raw", before.tree, after.tree))
        if (raw == null) { failed += before.root; return null }
        val changed = parseRaw(raw.toString(Charsets.UTF_8))
            .filter { it.isFile }
            .associateBy { File(top, it.path).path }
            .filterKeys { it !in known }
        val olds = changed.values.filterNot { it.added }.map { it.oldBlob }
        val contents = if (olds.isEmpty()) emptyMap() else blobs(top, deadline, olds)
        if (contents == null) { failed += before.root; return null }
        val out = LinkedHashMap<String, String?>()
        for ((path, change) in changed) {
            if (change.added) out[path] = null
            else contents[change.oldBlob]?.let { out[path] = it }
        }
        return out
    }

    override fun close() {
        indexes.values.forEach { it.delete() }
        indexes.clear()
    }

    /**
     * The repository [dir] lies in, asked of git once per folder; null outside a repository. `--git-path` prints the
     * index path relative to the folder git ran in unless it is absolute.
     */
    private fun repo(dir: String, deadline: Deadline): Repo? = repos[dir] ?: run {
        val lines = call(File(dir), deadline, listOf("rev-parse", "--show-toplevel", "--git-path", "index"))
            ?.toString(Charsets.UTF_8)?.lines()?.filter { it.isNotBlank() }
        if (lines == null || lines.size != 2) return null
        val index = File(lines[1]).takeIf(File::isAbsolute) ?: File(dir, lines[1])
        Repo(lines[0], index).also { repos[dir] = it }
    }

    /** A temporary copy of [realIndex]; a missing file when there is none yet, which git then starts afresh. */
    private fun seed(realIndex: File): File {
        val temp = File.createTempFile("tether-index", null).apply { deleteOnExit() }
        try {
            realIndex.copyTo(temp, overwrite = true)
        } catch (e: IOException) {
            if (e !is NoSuchFileException) throw e
            temp.delete()
        }
        return temp
    }

    /** The text of each blob in [ids] no bigger than [MAX_FILE_BYTES] and not binary; null when git fails. */
    private fun blobs(top: File, deadline: Deadline, ids: List<String>): Map<String, String>? {
        val sizes = call(top, deadline, listOf("cat-file", "--batch-check"), input = ids.joinToString("\n", postfix = "\n").toByteArray())
            ?: return null
        val small = sizes.toString(Charsets.UTF_8).lines().mapNotNull { line ->
            val (id, type, size) = line.split(' ').takeIf { it.size == 3 } ?: return@mapNotNull null
            id.takeIf { type == "blob" && (size.toLongOrNull() ?: Long.MAX_VALUE) <= MAX_FILE_BYTES }
        }
        if (small.isEmpty()) return emptyMap()
        val batch = call(top, deadline, listOf("cat-file", "--batch"), input = small.joinToString("\n", postfix = "\n").toByteArray())
            ?: return null
        return parseBatch(batch).filterValues { !it.contains(0) }.mapValues { it.value.toString(Charsets.UTF_8) }
    }

    /** Runs git with what is left of [deadline]; null without running it once the time is up. */
    private fun call(dir: File, deadline: Deadline, args: List<String>, env: Map<String, String> = emptyMap(), input: ByteArray? = null) =
        deadline.secondsLeft()?.let { git(dir, args, env, input, it) }

    private class Deadline(seconds: Long) {
        private val end = System.nanoTime() + seconds * 1_000_000_000

        /** Whole seconds left, rounded up; null once the time is up. */
        fun secondsLeft(): Long? = (end - System.nanoTime()).takeIf { it > 0 }?.let { (it + 999_999_999) / 1_000_000_000 }
    }

    /** One entry of `git diff-tree --raw -z`: a path relative to the root, the modes on both sides and the old blob. */
    internal data class RawChange(val path: String, val oldMode: String, val newMode: String, val oldBlob: String, val status: Char) {
        val added get() = status == 'A'
        /** A regular file on each side it exists on (not a symlink or submodule). */
        val isFile get() = listOf(oldMode, newMode).all { it == ABSENT || it.startsWith("100") }
    }

    companion object {
        /** Longest a snapshot may take; the command waits for it. */
        const val SNAPSHOT_SECONDS = 10L
        /** Longest reading what changed between two snapshots may take. */
        const val CHANGES_SECONDS = 20L
        /** Largest file whose content is kept for a diff. */
        const val MAX_FILE_BYTES = 2_000_000
        private const val ABSENT = "000000"

        /** Parses `git diff-tree -r -z --raw` output: `:<old mode> <new mode> <old sha> <new sha> <status>\0<path>\0` each. */
        internal fun parseRaw(raw: String): List<RawChange> {
            val fields = raw.split('\u0000').iterator()
            val out = ArrayList<RawChange>()
            while (fields.hasNext()) {
                val head = fields.next()
                if (!head.startsWith(":") || !fields.hasNext()) continue
                val path = fields.next()
                val parts = head.removePrefix(":").split(' ')
                if (parts.size < 5 || parts[4].isEmpty()) continue
                out += RawChange(path, parts[0], parts[1], parts[2], parts[4][0])
            }
            return out
        }

        /**
         * Parses `git cat-file --batch` output (`<id> <type> <size>\n<bytes>\n` each) into each object's bytes by id.
         * A `<id> missing` line, which has no size, is skipped.
         */
        internal fun parseBatch(out: ByteArray): Map<String, ByteArray> {
            val result = LinkedHashMap<String, ByteArray>()
            var i = 0
            while (i < out.size) {
                val eol = (i until out.size).firstOrNull { out[it] == '\n'.code.toByte() } ?: break
                val header = String(out, i, eol - i, Charsets.UTF_8).split(' ')
                val size = header.getOrNull(2)?.toIntOrNull()
                if (size == null) { i = eol + 1; continue }
                val start = eol + 1
                if (start + size > out.size) break
                result[header[0]] = out.copyOfRange(start, start + size)
                i = start + size + 1
            }
            return result
        }
    }
}
