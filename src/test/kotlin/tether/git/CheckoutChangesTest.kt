package tether.git

import tether.git.FileChange.Kind.ADDED
import tether.git.FileChange.Kind.DELETED
import tether.git.FileChange.Kind.MODIFIED
import tether.git.FileChange.Kind.RENAMED
import tether.testutil.git
import tether.testutil.gitAvailable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckoutChangesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesNameStatusAndUntrackedFiles() {
        val nameStatus = "M\u0000src/a.kt\u0000A\u0000new file.txt\u0000D\u0000gone.kt\u0000R087\u0000old/x.kt\u0000new/x.kt\u0000" +
            "T\u0000link\u0000C100\u0000orig.kt\u0000copy.kt\u0000U\u0000conflict.kt\u0000"
        val untracked = "notes/ü.md\u0000scratch.txt\u0000"
        assertEquals(
            listOf(
                FileChange("src/a.kt", MODIFIED),
                FileChange("new file.txt", ADDED),
                FileChange("gone.kt", DELETED),
                FileChange("new/x.kt", RENAMED, oldPath = "old/x.kt"),
                FileChange("link", MODIFIED),
                FileChange("copy.kt", ADDED),
                FileChange("conflict.kt", MODIFIED),
                FileChange("notes/ü.md", ADDED),
                FileChange("scratch.txt", ADDED),
            ),
            CheckoutChanges.parse(nameStatus, untracked),
        )
    }

    @Test fun emptyOutputMeansNoChanges() = assertEquals(emptyList<FileChange>(), CheckoutChanges.parse("", ""))

    @Test fun anUntrackedFileAlsoListedByDiffIsListedOnce() =
        assertEquals(listOf(FileChange("a", ADDED)), CheckoutChanges.parse("A\u0000a\u0000", "a\u0000"))

    // Real git: the base is resolved and every kind of change is listed.
    @Test fun realRepositoryBranchAgainstMain() {
        assumeTrue(gitAvailable())
        val repo = tmp.newFolder("repo")
        git(repo, "init", "-q", "-b", "main")
        File(repo, "keep.txt").writeText("one\n")
        File(repo, "edit.txt").writeText("before\n")
        File(repo, "drop.txt").writeText("bye\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "base")
        git(repo, "checkout", "-q", "-b", "feature")
        File(repo, "edit.txt").writeText("after\n")
        git(repo, "commit", "-q", "-am", "committed on the branch")
        File(repo, "drop.txt").delete()
        File(repo, "sub").mkdir()
        File(repo, "sub/new.txt").writeText("untracked\n")
        File(repo, ".gitignore").writeText("ignored.log\n")
        File(repo, "ignored.log").writeText("x\n")

        val bases = DiffBases()
        val base = bases.resolve(File(repo, "sub").path, null, null)!!
        assertEquals("main", base.since)
        assertEquals(repo.canonicalPath, File(base.root).canonicalPath)

        val changes = CheckoutChanges().list(base)!!.sortedBy { it.path }
        assertEquals(
            listOf(
                FileChange(".gitignore", ADDED),
                FileChange("drop.txt", DELETED),
                FileChange("edit.txt", MODIFIED),
                FileChange("sub/new.txt", ADDED),
            ),
            changes,
        )
        assertArrayEquals("before\n".toByteArray(), CheckoutChanges().baseContent(base, "edit.txt"))
    }

    @Test fun realRepositoryOnMainUsesTheSessionStart() {
        assumeTrue(gitAvailable())
        val repo = tmp.newFolder("repo")
        git(repo, "init", "-q", "-b", "main")
        File(repo, "a.txt").writeText("1\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "first")
        val start = DiffBases().head(repo.path)!!
        File(repo, "a.txt").writeText("2\n")
        git(repo, "commit", "-q", "-am", "during the session")

        val base = DiffBases().resolve(repo.path, start, null)!!
        assertEquals(DiffBase(base.root, start, "the session start"), base)
        assertEquals(listOf(FileChange("a.txt", MODIFIED)), CheckoutChanges().list(base))
    }
}
