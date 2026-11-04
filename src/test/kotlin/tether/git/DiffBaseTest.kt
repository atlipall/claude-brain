package tether.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DiffBaseTest {
    /** A fake git: answers by joined arguments; anything unknown fails (null). Records the folders it ran in. */
    private class FakeGit(vararg answers: Pair<String, String>) {
        val answers = mapOf(*answers)
        val dirs = mutableListOf<File>()
        fun run(dir: File, args: List<String>): String? {
            dirs += dir
            return answers[args.joinToString(" ")]
        }
    }

    private val top = "rev-parse --show-toplevel" to "/repo\n"
    private fun onBranch(name: String) = "symbolic-ref --short -q HEAD" to "$name\n"
    private val hasMain = "rev-parse --verify -q refs/heads/main" to "m1\n"
    private val hasMaster = "rev-parse --verify -q refs/heads/master" to "m2\n"
    private val head = "rev-parse --verify -q HEAD^{commit}" to "h1\n"

    private fun resolve(git: FakeGit, startCommit: String? = null, startedAt: Long? = null) =
        DiffBases(git::run).resolve("/repo/sub", startCommit, startedAt)

    @Test fun branchDiffsAgainstMergeBaseWithMain() {
        val git = FakeGit(top, onBranch("feature"), hasMain, head, "merge-base refs/heads/main HEAD" to "mb1\n")
        assertEquals(DiffBase("/repo", "mb1", "main"), resolve(git, startCommit = "s1"))
        assertEquals(File("/repo/sub"), git.dirs.first()) // finds the top level from the chat's folder
        assertEquals(setOf(File("/repo")), git.dirs.drop(1).toSet()) // then works at the top level
    }

    @Test fun branchUsesMasterWhenThereIsNoMain() {
        val git = FakeGit(top, onBranch("feature"), hasMaster, head, "merge-base refs/heads/master HEAD" to "mb2\n")
        assertEquals(DiffBase("/repo", "mb2", "master"), resolve(git))
    }

    @Test fun onMainUsesTheRecordedStartCommit() {
        val git = FakeGit(top, onBranch("main"), hasMain, head, "rev-parse --verify -q s1^{commit}" to "s1full\n")
        assertEquals(DiffBase("/repo", "s1full", "the session start"), resolve(git, startCommit = "s1", startedAt = 5_000))
    }

    @Test fun detachedHeadUsesTheSessionStart() {
        val git = FakeGit(top, hasMain, head, "rev-parse --verify -q s1^{commit}" to "s1full\n")
        assertEquals(DiffBase("/repo", "s1full", "the session start"), resolve(git, startCommit = "s1"))
    }

    @Test fun withoutAStartCommitFindsTheLastCommitBeforeTheSessionStarted() {
        val git = FakeGit(top, onBranch("main"), hasMain, head, "rev-list -1 --before=@1700000000 HEAD" to "old1\n")
        assertEquals(DiffBase("/repo", "old1", "the session start"), resolve(git, startedAt = 1_700_000_000_999))
    }

    @Test fun aStartCommitThatNoLongerExistsFallsBackToTheStartTime() {
        val git = FakeGit(top, onBranch("main"), hasMain, head, "rev-list -1 --before=@1700000000 HEAD" to "old1\n")
        assertEquals(DiffBase("/repo", "old1", "the session start"), resolve(git, startCommit = "gone", startedAt = 1_700_000_000_000))
    }

    @Test fun fallsBackToHeadWhenTheSessionStartIsUnknown() {
        val git = FakeGit(top, onBranch("main"), hasMain, head, "rev-list -1 --before=@1700000000 HEAD" to "\n")
        assertEquals(DiffBase("/repo", "h1", "HEAD"), resolve(git, startedAt = 1_700_000_000_000))
        assertEquals(DiffBase("/repo", "h1", "HEAD"), resolve(git))
    }

    @Test fun branchWithoutMainOrMasterUsesTheSessionStart() {
        val git = FakeGit(top, onBranch("feature"), head, "rev-parse --verify -q s1^{commit}" to "s1full\n")
        assertEquals(DiffBase("/repo", "s1full", "the session start"), resolve(git, startCommit = "s1"))
    }

    @Test fun nullOutsideARepositoryOrWithoutCommits() {
        assertNull(resolve(FakeGit()))
        assertNull(resolve(FakeGit(top, onBranch("main"))))
    }

    @Test fun titleNamesTheBaseAndShortCommit() =
        assertEquals("Changes since main (0123456)", DiffBase("/r", "0123456789abcdef", "main").title)

    @Test fun headIsTheCurrentCommitOrNull() {
        assertEquals("h1", DiffBases(FakeGit(head)::run).head("/repo"))
        assertNull(DiffBases(FakeGit()::run).head("/repo"))
    }
}
