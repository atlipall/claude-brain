package tether.image

import com.intellij.openapi.util.io.FileUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

/**
 * One chat's images on disk, so they can open in the IDE's image editor: the images attached to its messages and
 * those tool results showed without naming a file. Everything lives in one folder of its own under [parent], made
 * on the first write and removed by [delete], after which nothing more is written. Each image gets a subfolder, so
 * it keeps its own name (and the tab title shows it) even when two share one. Thread-safe.
 */
class ImageFiles(private val parent: Path = DEFAULT_PARENT) {
    private var dir: Path? = null
    private var deleted = false
    private var seq = 0
    private val byKey = HashMap<String, Path>()

    /**
     * Writes [bytes] as [name] (its folders dropped and its extension made [type]'s) and returns the file, or null when
     * it can't be written or [delete] has run. With a [key], an image already written under that key is returned as it
     * is instead.
     */
    @Synchronized
    fun write(name: String, type: ImageType, bytes: ByteArray, key: String? = null): Path? {
        if (deleted) return null
        key?.let(byKey::get)?.takeIf { it.exists() }?.let { return it }
        return try {
            val folder = Files.createDirectory(folder().resolve((++seq).toString()))
            folder.resolve(fileName(name, type)).also { file ->
                file.writeBytes(bytes)
                if (key != null) byKey[key] = file
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Removes every file written so far and refuses later writes: the chat they belong to is gone. */
    @Synchronized
    fun delete() {
        deleted = true
        dir?.toFile()?.deleteRecursively()
        dir = null
        byKey.clear()
    }

    private fun folder(): Path = dir ?: Files.createTempDirectory(parent.createDirectories(), "chat-").also { dir = it }

    companion object {
        /** Where chats' image folders go unless told otherwise: `tether-images` in the system temp folder. */
        val DEFAULT_PARENT: Path = Path(System.getProperty("java.io.tmpdir"), "tether-images")

        /**
         * [name] without its folders, extension or edge dots, and with characters no file system allows replaced
         * (`image` when nothing is left), with [type]'s extension.
         */
        internal fun fileName(name: String, type: ImageType): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().trim('.')
            return "${FileUtil.sanitizeFileName(base, false).ifEmpty { "image" }}.${type.extension}"
        }
    }
}
