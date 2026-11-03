package claudechat.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun trailingSlashIsIgnored() {
        val dir = tmp.newFolder("dir")
        assertEquals(canonicalPath(dir.path), canonicalPath(dir.path + "/"))
    }

    @Test fun symlinkAndItsTargetCanonicaliseEqual() {
        val target = tmp.newFolder("target")
        val link = File(tmp.root, "link").toPath()
        Files.createSymbolicLink(link, target.toPath())
        assertEquals(canonicalPath(target.path), canonicalPath(link.toString()))
    }

    @Test fun nonExistentPathGivesNormalisedStringWithoutThrowing() {
        val missing = File(tmp.root, "missing/child").path
        val result = canonicalPath("$missing/")
        assertTrue(result, result.endsWith("missing/child"))
        assertTrue(result, !result.endsWith("/"))
    }

    @Test fun rootStaysRoot() = assertEquals("/", canonicalPath("/"))
}
