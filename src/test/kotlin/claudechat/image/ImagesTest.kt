package claudechat.image

import claudechat.testutil.TestImages
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs as if no WebP reader were installed (the bare JDK); `ImagesWebpTest` covers the IDE's WebP reader. */
class ImagesTest {
    private lateinit var savedWebp: () -> Boolean

    @Before fun noWebpReader() {
        savedWebp = Images.webpDecodable
        Images.webpDecodable = { false }
    }

    @After fun restoreWebpReader() {
        Images.webpDecodable = savedWebp
    }

    private fun ok(p: Prepared): Image = (p as? Prepared.Ok)?.image ?: error("expected Ok, got ${(p as Prepared.Rejected).reason}")
    private fun reason(p: Prepared): String = (p as? Prepared.Rejected)?.reason ?: error("expected Rejected")

    @Test fun sniffsMagicBytesOnly() {
        assertEquals(ImageType.PNG, ImageType.sniff(TestImages.bytes(TestImages.BLUE_PNG_BASE64)))
        assertEquals(ImageType.JPEG, ImageType.sniff(TestImages.solid(2, 2, "jpeg")))
        assertEquals(ImageType.GIF, ImageType.sniff(TestImages.solid(2, 2, "gif")))
        assertEquals(ImageType.WEBP, ImageType.sniff(TestImages.webpHeader("VP8L", 1, 1)))
        assertNull(ImageType.sniff("hello world".toByteArray()))
        assertNull(ImageType.sniff(ByteArray(0)))
        assertNull(ImageType.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
    }

    @Test fun dimensionsComeFromTheHeader() {
        assertEquals(4 to 4, Images.dimensions(TestImages.bytes(TestImages.BLUE_PNG_BASE64), ImageType.PNG))
        assertEquals(30 to 20, Images.dimensions(TestImages.solid(30, 20, "jpeg"), ImageType.JPEG))
        assertEquals(7 to 3, Images.dimensions(TestImages.solid(7, 3, "gif"), ImageType.GIF))
        assertEquals(640 to 480, Images.dimensions(TestImages.webpHeader("VP8 ", 640, 480), ImageType.WEBP))
        assertEquals(3000 to 17, Images.dimensions(TestImages.webpHeader("VP8L", 3000, 17), ImageType.WEBP))
        assertEquals(5000 to 4000, Images.dimensions(TestImages.webpHeader("VP8X", 5000, 4000), ImageType.WEBP))
        assertNull(Images.dimensions(TestImages.bytes(TestImages.BLUE_PNG_BASE64).copyOf(12), ImageType.PNG))
    }

    @Test fun smallImagesAreSentAsTheyAre() {
        val bytes = TestImages.bytes(TestImages.BLUE_PNG_BASE64)
        val image = ok(Images.prepare(bytes))
        assertSame(bytes, image.bytes)
        assertEquals(ImageType.PNG, image.type)
        assertEquals("data:image/png;base64,${TestImages.BLUE_PNG_BASE64}", image.dataUrl())
        assertEquals(TestImages.BLUE_PNG_BASE64, image.base64())
        assertEquals(ImageType.WEBP, ok(Images.prepare(TestImages.webpHeader("VP8X", 2000, 1000))).type)
    }

    @Test fun truncatedOrCorruptImagesAreRefusedEvenWhenSmall() {
        for (format in listOf("png", "gif", "jpeg")) {
            val whole = TestImages.noiseIn(64, 64, format)
            assertTrue(format, Images.prepare(whole) is Prepared.Ok)
            val cut = Images.prepare(whole.copyOf(whole.size / 2))
            assertTrue("$format was accepted", cut is Prepared.Rejected)
            assertEquals(format, Images.UNREADABLE, reason(cut))
        }
    }

    @Test fun jpegWithDataAfterTheEndMarkerIsAccepted() {
        val whole = TestImages.noiseIn(64, 64, "jpeg")
        assertTrue(Images.prepare(whole + ByteArray(1024) { (it * 7).toByte() }) is Prepared.Ok)
        assertTrue(Images.prepare(whole + "\n".toByteArray()) is Prepared.Ok)
        assertTrue(Images.preview(whole + "\n".toByteArray()) != null)
        assertTrue(Images.prepare(whole.copyOf(whole.size / 2)) is Prepared.Rejected)
    }

    @Test fun trailerContainingAStartOfScanMarkerDoesNotCauseARefusal() {
        val whole = TestImages.noiseIn(64, 64, "jpeg")
        val trailer = byteArrayOf(0x00, 0xFF.toByte(), 0xDA.toByte(), 0x12, 0x34)
        assertTrue(Images.prepare(whole + trailer) is Prepared.Ok)
    }

    @Test fun aLongEdgeOver2000IsScaledDownKeepingTheFormat() {
        val png = ok(Images.prepare(TestImages.solid(3000, 1500)))
        assertEquals(ImageType.PNG, png.type)
        assertEquals(2000 to 1000, png.width to png.height)
        assertEquals(2000 to 1000, Images.dimensions(png.bytes, ImageType.PNG))
        val jpeg = ok(Images.prepare(TestImages.solid(1000, 2400, "jpeg")))
        assertEquals(ImageType.JPEG, jpeg.type)
        assertEquals(833 to 2000, jpeg.width to jpeg.height)
    }

    @Test fun gifIsReencodedAsPngWhenItNeedsScaling() {
        val image = ok(Images.prepare(TestImages.solid(2500, 100, "gif")))
        assertEquals(ImageType.PNG, image.type)
        assertEquals(2000 to 80, image.width to image.height)
    }

    @Test fun pngThatStaysTooLargeBecomesJpeg() {
        val noise = TestImages.noise(300, 300)
        assertTrue(noise.size > 200_000)
        val image = ok(Images.prepare(noise, maxBytes = 200_000))
        assertEquals(ImageType.JPEG, image.type)
        assertEquals(300 to 300, image.width to image.height)
        assertTrue(image.bytes.size <= 200_000)
    }

    @Test fun rejections() {
        assertEquals(Images.NOT_AN_IMAGE, reason(Images.prepare("not an image".toByteArray())))
        assertEquals(Images.UNREADABLE, reason(Images.prepare(TestImages.bytes(TestImages.BLUE_PNG_BASE64).copyOf(12))))
        assertEquals(Images.WEBP_TOO_LARGE, reason(Images.prepare(TestImages.webpHeader("VP8X", 2001, 10))))
        assertEquals(Images.TOO_MANY_PIXELS, reason(Images.prepare(claimSize(TestImages.solid(1, 1)))))
        assertEquals(Images.TOO_LARGE, reason(Images.prepare(TestImages.noise(300, 300), maxBytes = 100)))
    }

    @Test fun previewKeepsSmallImagesAndShrinksLargeOnes() {
        val small = TestImages.bytes(TestImages.BLUE_PNG_BASE64)
        assertSame(small, Images.preview(small)!!.bytes)
        val big = Images.preview(TestImages.solid(1600, 400))!!
        assertEquals(800 to 200, big.width to big.height)
        val thumb = Images.preview(TestImages.solid(400, 400, "jpeg"), Images.THUMB_EDGE, Images.THUMB_BYTES)!!
        assertEquals(ImageType.JPEG, thumb.type)
        assertEquals(160 to 160, thumb.width to thumb.height)
        val webp = TestImages.webpHeader("VP8X", 4000, 3000)
        assertArrayEquals(webp, Images.preview(webp)!!.bytes) // the page scales it
        assertNull(Images.preview(webp, maxBytes = 10))
        assertNull(Images.preview("nope".toByteArray()))
        assertNull(Images.preview(TestImages.noise(300, 300), maxBytes = 100))
    }

    /** A real 1x1 PNG whose header claims 10000x10000: must be refused before decoding. */
    private fun claimSize(png: ByteArray): ByteArray = png.copyOf().also { b ->
        fun be32(at: Int, v: Int) = repeat(4) { i -> b[at + i] = (v shr (8 * (3 - i))).toByte() }
        be32(16, 10_000) // IHDR width
        be32(20, 10_000) // IHDR height
    }
}
