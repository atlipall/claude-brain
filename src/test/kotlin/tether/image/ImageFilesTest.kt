package tether.image

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes

class ImageFilesTest {
    private val parent = Files.createTempDirectory("image-files-test")
    private val files = ImageFiles(parent)
    private val png = byteArrayOf(1, 2, 3)

    @After fun cleanUp() {
        parent.toFile().deleteRecursively()
    }

    @Test fun aWrittenImageKeepsItsBytesAndNameUnderTheParent() {
        val path = files.write("Screenshot 2026-11-07.png", ImageType.PNG, png)!!
        assertArrayEquals(png, path.readBytes())
        assertEquals("Screenshot 2026-11-07.png", path.name)
        assertTrue(path.startsWith(parent))
    }

    @Test fun theExtensionFollowsTheBytesNotTheName() {
        assertEquals("shot.jpg", files.write("shot.png", ImageType.JPEG, png)!!.name)
        assertEquals("anim.gif", files.write("anim", ImageType.GIF, png)!!.name)
    }

    @Test fun pathsAndBlankNamesCannotLeaveTheFolder() {
        assertEquals("evil.png", files.write("../../evil.png", ImageType.PNG, png)!!.name)
        assertEquals("evil.png", files.write("..\\..\\evil.png", ImageType.PNG, png)!!.name)
        assertEquals("image.png", files.write("", ImageType.PNG, png)!!.name)
        assertEquals("image.png", files.write("..", ImageType.PNG, png)!!.name)
    }

    @Test fun charactersSomeFileSystemsRefuseAreReplaced() {
        val name = files.write("what: a <shot>?.png", ImageType.PNG, png)!!.name
        assertFalse(name.any { it in ":<>?" })
        assertTrue(name.startsWith("what") && name.endsWith(".png"))
    }

    @Test fun twoImagesWithTheSameNameGetTheirOwnFiles() {
        val a = files.write("image.png", ImageType.PNG, png)!!
        val b = files.write("image.png", ImageType.PNG, byteArrayOf(9))!!
        assertNotEquals(a, b)
        assertArrayEquals(png, a.readBytes())
    }

    @Test fun aKeyedImageIsWrittenOnce() {
        val a = files.write("image.png", ImageType.PNG, png, key = "tool-1:0")!!
        val b = files.write("image.png", ImageType.PNG, byteArrayOf(9), key = "tool-1:0")!!
        assertEquals(a, b)
        assertArrayEquals(png, b.readBytes())
    }

    @Test fun deleteRemovesEveryFileAndRefusesLaterWrites() {
        val a = files.write("a.png", ImageType.PNG, png)!!
        files.delete()
        assertFalse(a.exists())
        assertNull(files.write("b.png", ImageType.PNG, png))
        assertTrue(parent.toFile().list()!!.isEmpty())
    }
}
