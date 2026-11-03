package claudechat.image

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Images arriving from the page as base64 chunks (one bridge message each). Each chunk is decoded on arrival, so a
 * chunk's length must be a multiple of 4 (the page sends 262144 characters). Not thread-safe: EDT only.
 */
class Uploads(private val maxBytes: Int = Images.MAX_INPUT_BYTES) {
    private class Upload(val name: String) {
        val bytes = ByteArrayOutputStream()
    }

    private val active = HashMap<String, Upload>()

    val count: Int get() = active.size

    fun has(id: String): Boolean = id in active

    fun start(id: String, name: String) {
        active[id] = Upload(name)
    }

    /** Appends a chunk. False — and the upload is dropped — when it is not base64 or the total would pass the cap. */
    fun chunk(id: String, base64: String): Boolean {
        val upload = active[id] ?: return false
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            active.remove(id)
            return false
        }
        if (upload.bytes.size() + bytes.size > maxBytes) {
            active.remove(id)
            return false
        }
        upload.bytes.writeBytes(bytes)
        return true
    }

    /** The name and bytes of a complete upload, or null when it is unknown (refused, cancelled or never started). */
    fun finish(id: String): Pair<String, ByteArray>? = active.remove(id)?.let { it.name to it.bytes.toByteArray() }

    fun cancel(id: String) {
        active.remove(id)
    }

    fun clear() = active.clear()
}
