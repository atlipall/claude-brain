package tether.session

/**
 * The files on disk of user messages' images, by chat item id, so a click on one opens it in the editor (the items
 * themselves carry only thumbnails). Not thread-safe: the session that owns it guards it with its own lock.
 */
internal class SentImageFiles {
    private val byItem = HashMap<String, List<String>>()

    /** Keeps [files] (paths, "" for an image without one) for user message [itemId], unless none of them is a path. */
    fun keep(itemId: String, files: List<String>) {
        if (files.any { it.isNotEmpty() }) byItem[itemId] = files
    }

    /** The path of image [index] of user message [itemId], or null when it has none. */
    fun get(itemId: String, index: Int): String? = byItem[itemId]?.getOrNull(index)?.takeIf { it.isNotEmpty() }

    /** Forgets user message [itemId]'s files (a rewind took the message back). */
    fun drop(itemId: String) {
        byItem.remove(itemId)
    }
}
