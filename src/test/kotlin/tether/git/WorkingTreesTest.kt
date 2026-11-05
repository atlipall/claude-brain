package tether.git

import tether.testutil.git
import tether.testutil.gitAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkingTreesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val trees = WorkingTrees()
    private lateinit var repo: File

    @After fun tearDown() = trees.close()

    @Before fun setUp() {
        assumeTrue(gitAvailable())
        repo = tmp.newFolder("repo").canonicalFile
        git(repo, "init", "-q")
        File(repo, ".gitignore").writeText("build/\n")
        File(repo, "a.txt").writeText("one\n")
        File(repo, "gone.txt").writeText("bye\n")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", "init")
    }

    private fun path(name: String) = File(repo, name).path

    @Test fun listsChangedAddedAndDeletedFilesWithTheirContentBefore() {
        File(repo, "untracked.txt").writeText("u1\n")
        val before = trees.snapshot(repo.path)!!
        File(repo, "a.txt").writeText("two\n")
        File(repo, "untracked.txt").writeText("u2\n")
        File(repo, "new.txt").writeText("n\n")
        File(repo, "gone.txt").delete()
        val after = trees.snapshot(repo.path)!!
        assertEquals(
            mapOf(path("a.txt") to "one\n", path("gone.txt") to "bye\n", path("new.txt") to null, path("untracked.txt") to "u1\n"),
            trees.changes(before, after),
        )
    }

    @Test fun ignoredAndBinaryFilesAreLeftOut() {
        File(repo, "bin.dat").writeBytes(byteArrayOf(1, 0, 2))
        val before = trees.snapshot(repo.path)!!
        File(repo, "build").mkdir()
        File(repo, "build/out.txt").writeText("x\n")
        File(repo, "bin.dat").writeBytes(byteArrayOf(3, 0, 4))
        assertEquals(emptyMap<String, String?>(), trees.changes(before, trees.snapshot(repo.path)!!))
    }

    @Test fun theIndexAndHeadAreLeftAlone() {
        File(repo, "a.txt").writeText("staged\n")
        git(repo, "add", "a.txt")
        File(repo, "a.txt").writeText("unstaged\n")
        File(repo, "untracked.txt").writeText("u\n")
        val status = { String(ProcessBuilder("git", "status", "--porcelain").directory(repo).start().inputStream.readBytes()) }
        val head = { String(ProcessBuilder("git", "rev-parse", "HEAD").directory(repo).start().inputStream.readBytes()) }
        val (s0, h0) = status() to head()
        assertNotNull(trees.snapshot(repo.path))
        assertEquals(s0, status())
        assertEquals(h0, head())
    }

    @Test fun aSubfolderSnapshotsTheWholeRepository() {
        File(repo, "sub").mkdir()
        val before = trees.snapshot(File(repo, "sub").path)!!
        File(repo, "a.txt").writeText("two\n")
        assertEquals(mapOf(path("a.txt") to "one\n"), trees.changes(before, trees.snapshot(repo.path)!!))
    }

    @Test fun knownPathsAreSkippedAndAnUnchangedTreeHasNoChanges() {
        val before = trees.snapshot(repo.path)!!
        assertEquals(emptyMap<String, String?>(), trees.changes(before, trees.snapshot(repo.path)!!))
        File(repo, "a.txt").writeText("two\n")
        File(repo, "gone.txt").writeText("changed\n")
        assertEquals(mapOf(path("gone.txt") to "bye\n"), trees.changes(before, trees.snapshot(repo.path)!!, known = setOf(path("a.txt"))))
    }

    @Test fun laterSnapshotsSeeChangesToUntrackedFiles() {
        File(repo, "u.txt").writeText("1\n")
        val first = trees.snapshot(repo.path)!!
        File(repo, "u.txt").writeText("2\n")
        val second = trees.snapshot(repo.path)!!
        File(repo, "u.txt").writeText("3\n")
        assertEquals(mapOf(path("u.txt") to "2\n"), trees.changes(second, trees.snapshot(repo.path)!!))
        assertEquals(mapOf(path("u.txt") to "1\n"), trees.changes(first, second))
    }

    @Test fun parsesCatFileBatchOutput() {
        val out = "aaa blob 3\nx\ny\nbbb missing\nccc blob 0\n\n".toByteArray()
        val parsed = WorkingTrees.parseBatch(out)
        assertEquals(listOf("aaa", "ccc"), parsed.keys.toList())
        assertEquals("x\ny", String(parsed["aaa"]!!))
        assertEquals("", String(parsed["ccc"]!!))
    }

    @Test fun outsideARepositoryThereIsNoSnapshot() {
        assertNull(trees.snapshot(tmp.newFolder("plain").path))
    }

    @Test fun parsesRawDiffTreeOutput() {
        val raw = ":100644 100644 aaa bbb M\u0000a b.txt\u0000:000000 100644 000 ccc A\u0000n.txt\u0000:120000 120000 d e M\u0000link\u0000"
        val changes = WorkingTrees.parseRaw(raw)
        assertEquals(listOf("a b.txt", "n.txt", "link"), changes.map { it.path })
        assertEquals(listOf(true, true, false), changes.map { it.isFile })
        assertEquals(listOf(false, true, false), changes.map { it.added })
        assertEquals("aaa", changes[0].oldBlob)
    }
}
