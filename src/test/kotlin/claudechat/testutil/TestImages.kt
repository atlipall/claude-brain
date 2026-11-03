package claudechat.testutil

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import javax.imageio.ImageIO

/** Image bytes for tests. */
object TestImages {
    /** The 4x4 blue PNG Claude's Read tool returned in the protocol probe (73 bytes). */
    const val BLUE_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGNgYPiPhIjiAACOsw/xs6MvMwAAAABJRU5ErkJggg=="

    /** The 4x4 red PNG the probe sent as an image block. */
    const val RED_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg=="

    fun bytes(base64: String): ByteArray = Base64.getDecoder().decode(base64)

    /** A one-colour image in an ImageIO format ("png", "jpeg", "gif"). */
    fun solid(w: Int, h: Int, format: String = "png", color: Color = Color.BLUE): ByteArray {
        val img = BufferedImage(w, h, if (format == "png") BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, w, h)
        g.dispose()
        return ByteArrayOutputStream().also { check(ImageIO.write(img, format, it)) }.toByteArray()
    }

    /** A PNG of random pixels: PNG can't compress it, JPEG can. */
    fun noise(w: Int, h: Int, seed: Long = 1): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val random = Random(seed)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, random.nextInt(0x1000000))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** The first 30 bytes of a WebP file of the given chunk kind ("VP8 ", "VP8L" or "VP8X") and size; enough for its header. */
    fun webpHeader(kind: String, w: Int, h: Int): ByteArray {
        val b = ByteArray(30)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun le(at: Int, value: Long, n: Int) = repeat(n) { i -> b[at + i] = (value shr (8 * i)).toByte() }
        ascii(0, "RIFF"); le(4, 22, 4); ascii(8, "WEBP"); ascii(12, kind); le(16, 10, 4)
        when (kind) {
            "VP8 " -> { b[23] = 0x9D.toByte(); b[24] = 0x01; b[25] = 0x2A; le(26, w.toLong(), 2); le(28, h.toLong(), 2) }
            "VP8L" -> { b[20] = 0x2F; le(21, (w - 1).toLong() or ((h - 1).toLong() shl 14), 4) }
            "VP8X" -> { le(24, (w - 1).toLong(), 3); le(27, (h - 1).toLong(), 3) }
        }
        return b
    }
}
