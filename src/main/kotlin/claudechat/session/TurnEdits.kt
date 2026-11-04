package claudechat.session

import com.intellij.util.diff.Diff
import com.intellij.util.diff.FilesTooBigForDiffException
import java.io.File
import java.io.IOException

/**
 * The files one turn's edit tools changed, each with its content from before the turn's first edit of it (taken from
 * the CLI's tool results). Not thread-safe: [ChatSession]'s lock guards it.
 */
internal class TurnEdits {
    /** Absolute path -> content before the turn first edited it; null for a file the turn created. In first-edit order. */
    private val originals = LinkedHashMap<String, String?>()

    fun record(path: String, original: String?) {
        if (path !in originals) originals[path] = original
    }

    /** The files recorded so far, whose originals are kept. */
    fun paths(): Set<String> = originals.keys.toSet()

    /** The turn's originals, and starts over for the next turn. */
    fun take(): Map<String, String?> = LinkedHashMap(originals).also { originals.clear() }

    fun clear() = originals.clear()

    companion object {
        /** The files of [originals] that differ on disk now, each with its diff. Reads the disk, so not under a lock. */
        fun summarize(originals: Map<String, String?>): List<Pair<TurnFile, FileDiff>> =
            originals.mapNotNull { (path, original) ->
                val (added, removed) = lineCounts(original.orEmpty(), readOrNull(path).orEmpty())
                if (added == 0 && removed == 0) null
                else TurnFile(path, added, removed, isNew = original == null) to FileDiff(path, original.orEmpty(), isNew = original == null)
            }

        /** Lines added and removed going from [before] to [after]; a file too big to diff counts as wholly replaced. */
        fun lineCounts(before: String, after: String): Pair<Int, Int> {
            val old = lines(before)
            val new = lines(after)
            var change = try {
                Diff.buildChanges(old, new)
            } catch (e: FilesTooBigForDiffException) {
                return new.size to old.size
            }
            var added = 0
            var removed = 0
            while (change != null) {
                added += change.inserted
                removed += change.deleted
                change = change.link
            }
            return added to removed
        }

        private fun lines(text: String): Array<String> =
            if (text.isEmpty()) emptyArray() else text.removeSuffix("\n").split("\n").toTypedArray()

        private fun readOrNull(path: String): String? = try {
            File(path).takeIf { it.isFile }?.readText()
        } catch (e: IOException) {
            null
        }
    }
}
