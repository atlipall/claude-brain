package tether.image

import tether.testutil.TestImages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadsTest {
    private val b64 = TestImages.BLUE_PNG_BASE64 // 100 characters

    @Test fun chunksAreJoined() {
        val uploads = Uploads()
        uploads.start("p1", "a.png")
        assertTrue(uploads.chunk("p1", b64.substring(0, 64)))
        assertTrue(uploads.chunk("p1", b64.substring(64)))
        assertEquals(1, uploads.count)
        val (name, bytes) = uploads.finish("p1")!!
        assertEquals("a.png", name)
        assertArrayEquals(TestImages.bytes(b64), bytes)
        assertEquals(0, uploads.count)
        assertNull(uploads.finish("p1"))
    }

    @Test fun unknownCancelledAndClearedUploadsTakeNoChunks() {
        val uploads = Uploads()
        assertFalse(uploads.chunk("nope", b64))
        uploads.start("p1", "a.png")
        uploads.cancel("p1")
        assertFalse(uploads.has("p1"))
        assertNull(uploads.finish("p1"))
        uploads.start("p2", "b.png")
        uploads.clear()
        assertFalse(uploads.chunk("p2", b64))
    }

    @Test fun badBase64DropsTheUpload() {
        val uploads = Uploads()
        uploads.start("p1", "a.png")
        assertFalse(uploads.chunk("p1", "not base64!"))
        assertFalse(uploads.has("p1"))
    }

    @Test fun passingTheCapDropsTheUpload() {
        val uploads = Uploads(maxBytes = 60)
        uploads.start("p1", "a.png")
        assertTrue(uploads.chunk("p1", b64.substring(0, 64))) // 48 bytes
        assertFalse(uploads.chunk("p1", b64.substring(64)))   // 73 bytes in all
        assertFalse(uploads.has("p1"))
    }
}
