package claudechat.follow

/** A zero-based caret position. */
data class Caret(val line: Int, val column: Int) {
    /** This position moved inside a document of [lineCount] lines (at least one) whose line lengths [lineLength] gives. */
    fun clamp(lineCount: Int, lineLength: (Int) -> Int): Caret {
        val l = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        return Caret(l, column.coerceIn(0, lineLength(l).coerceAtLeast(0)))
    }
}

/** An open editor tab: its file path and, for a text editor, its caret. */
data class OpenFile(val path: String, val caret: Caret?)

/** Replace the tab showing [from] with [to] in the same place, putting the caret at [caret] (clamped) when known. */
data class Replacement(val from: String, val to: String, val caret: Caret?)

object EditorSwapPlan {
    /**
     * Which open tabs to swap when the IDE moves from checkout [from] to [to]: every file in [from] whose counterpart
     * [exists] and is not already open. Files outside [from], without a counterpart, or whose counterpart already has a
     * tab stay as they are. Each file appears once, in [open] order.
     */
    fun plan(open: List<OpenFile>, from: Checkout, to: Checkout, mapper: CheckoutMapper, exists: (String) -> Boolean): List<Replacement> {
        if (from == to) return emptyList()
        val openPaths = open.map { it.path }.toSet()
        return open.distinctBy { it.path }.mapNotNull { file ->
            val target = mapper.counterpart(file.path, from, to) ?: return@mapNotNull null
            if (target in openPaths || !exists(target)) null else Replacement(file.path, target, file.caret)
        }
    }
}
