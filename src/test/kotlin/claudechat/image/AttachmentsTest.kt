package claudechat.image

import claudechat.testutil.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private val image = Image(ImageType.PNG, TestImages.bytes(TestImages.BLUE_PNG_BASE64), 4, 4)
    private fun att(id: String) = Attachment(id, "$id.png", image, "")

    @Test fun takeReturnsTheRequestedOnesInOrderAndRemovesThem() {
        val pending = PendingAttachments()
        listOf("a", "b", "c").forEach { assertTrue(pending.add(att(it))) }
        assertEquals(listOf("c", "a"), pending.take(listOf("c", "x", "a", "c")).map { it.id })
        assertEquals(1, pending.size)
        assertEquals(emptyList<String>(), pending.take(listOf("a")).map { it.id })
    }

    @Test fun atMostMaxAreKept() {
        val pending = PendingAttachments(max = 2)
        assertTrue(pending.add(att("a")))
        assertTrue(pending.add(att("b")))
        assertFalse(pending.add(att("c")))
        assertTrue(pending.add(att("a"))) // replacing one is not adding one
        pending.remove("a")
        assertTrue(pending.add(att("c")))
        pending.clear()
        assertEquals(0, pending.size)
    }

    @Test fun theDefaultLimitIsTen() {
        val pending = PendingAttachments()
        repeat(10) { assertTrue(pending.add(att("i$it"))) }
        assertFalse(pending.add(att("eleventh")))
    }
}
