package claudechat.image

/** An image waiting above the message box: validated bytes for Claude and a small [thumb] (`data:` URL, or "" when none could be made) for the page. */
class Attachment(val id: String, val name: String, val image: Image, val thumb: String)

/** One chat's attachments for its next message, in the order they were added. Not thread-safe: EDT only. */
class PendingAttachments(private val max: Int = Images.MAX_PER_MESSAGE) {
    private val items = LinkedHashMap<String, Attachment>()

    val size: Int get() = items.size

    /** False when [max] attachments are already waiting. */
    fun add(attachment: Attachment): Boolean {
        if (attachment.id !in items && items.size >= max) return false
        items[attachment.id] = attachment
        return true
    }

    fun remove(id: String) {
        items.remove(id)
    }

    /** Removes and returns the attachments with these ids, in the given order; unknown ids are skipped. */
    fun take(ids: List<String>): List<Attachment> = ids.distinct().mapNotNull { items.remove(it) }

    fun clear() = items.clear()
}
