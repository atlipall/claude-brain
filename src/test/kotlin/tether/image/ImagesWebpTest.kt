package tether.image

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Inside the IDE the bundled WebP plugin registers an ImageIO reader and writer, so WebP is scaled like GIF. */
class ImagesWebpTest : BasePlatformTestCase() {
    private fun webp(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(img, "webp", it)) }.toByteArray()
    }

    fun testTheIdeCanDecodeWebp() {
        assertTrue(Images.webpDecodable())
        assertEquals(ImageType.WEBP, ImageType.sniff(webp(30, 20)))
        assertEquals(30 to 20, Images.dimensions(webp(30, 20), ImageType.WEBP))
    }

    fun testSmallWebpIsSentAsItIs() {
        val bytes = webp(30, 20)
        assertSame(bytes, (Images.prepare(bytes) as Prepared.Ok).image.bytes)
    }

    fun testLargeWebpIsScaledAndReencodedAsPng() {
        val image = (Images.prepare(webp(3000, 100)) as Prepared.Ok).image
        assertEquals(ImageType.PNG, image.type)
        assertEquals(2000 to 67, image.width to image.height)
    }

    fun testWebpPreviewIsScaled() {
        val preview = Images.preview(webp(1600, 400))!!
        assertEquals(ImageType.PNG, preview.type)
        assertEquals(800 to 200, preview.width to preview.height)
    }
}
