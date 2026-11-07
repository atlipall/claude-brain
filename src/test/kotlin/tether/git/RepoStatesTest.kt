package tether.git

import tether.testutil.git
import tether.testutil.gitAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepoStatesTest {
    private val states = RepoStates()
    private lateinit var remote: File
    private lateinit var work: File
    private lateinit var other: File

    /**
     * A bare remote, a clone [work] on `main` with one pushed commit, and a second clone [other] of it.
     * [work] gets its own committer identity so git commands the code under test runs there, such as a merge,
     * work on machines without a global git identity, like CI runners.
     */
    @Before fun setUp() {
        assumeTrue(gitAvailable())
        val top = Files.createTempDirectory("repo-states").toFile()
        remote = File(top, "remote.git")
        work = File(top, "work")
        other = File(top, "other")
        git(top, "init", "-q", "--bare", "-b", "main", remote.path)
        git(top, "clone", "-q", remote.path, work.path)
        git(work, "config", "user.name", "t")
        git(work, "config", "user.email", "t@t")
        commit(work, "one")
        git(work, "push", "-q", "-u", "origin", "main")
        git(top, "clone", "-q", remote.path, other.path)
    }

    private fun commit(dir: File, message: String) = git(dir, "commit", "-q", "--allow-empty", "-m", message)
    private fun short(dir: File, rev: String = "HEAD") = runGit(dir, "rev-parse", "--short", rev)!!.trim()
    private fun read(dir: File = work) = states.read(dir.path)!!

    /** Runs [action] and returns the note describing what it changed in [work]. */
    private fun noteAfter(action: () -> Unit): String? {
        val before = read()
        action()
        return states.describe(work.path, before, read())
    }

    @Test fun outsideARepositoryThereIsNoState() {
        assertNull(states.read(Files.createTempDirectory("plain").toFile().path))
        assertNull(states.read("/does/not/exist"))
    }

    @Test fun nothingChangedGivesNoNote() {
        assertNull(noteAfter {})
    }

    @Test fun readsTheBranchHeadAndUpstream() {
        val state = read()
        assertEquals("main", state.branch)
        assertEquals("origin/main", state.upstream)
        assertEquals(state.head, state.upstreamHead)
        assertEquals(0, state.stashes)
        assertNull(state.operation)
    }

    @Test fun reportsNewCommits() {
        val note = noteAfter { commit(work, "two"); commit(work, "three") }!!
        assertTrue(note, note.startsWith("[IDE] Since your last turn, git changed in this checkout"))
        assertTrue(note, note.contains("- 2 new commits on `main`: `${short(work)}` “three”; `${short(work, "HEAD~1")}` “two”."))
    }

    @Test fun reportsAPushSeparatelyFromTheCommit() {
        val note = noteAfter { commit(work, "two"); git(work, "push", "-q") }!!
        assertTrue(note, note.contains("1 new commit on `main`"))
        assertTrue(note, note.contains("- Pushed to `origin/main`, now at `${short(work)}` “two”."))
    }

    @Test fun reportsAPullAsTheUpstreamMovingNotAPush() {
        commit(other, "theirs")
        git(other, "push", "-q")
        val note = noteAfter { git(work, "pull", "-q") }!!
        assertTrue(note, note.contains("1 new commit on `main`: `${short(work)}` “theirs”."))
        assertTrue(note, note.contains("`origin/main` moved to `${short(work)}` “theirs” (fetched or pulled); `main` is 0 commits ahead and 0 behind it."))
        assertTrue(note, !note.contains("Pushed"))
    }

    @Test fun reportsAFetchThatLeavesTheBranchBehind() {
        commit(other, "theirs")
        git(other, "push", "-q")
        val note = noteAfter { git(work, "fetch", "-q") }!!
        assertTrue(note, note.contains("`main` is 0 commits ahead and 1 behind it."))
    }

    @Test fun reportsABranchSwitch() {
        val note = noteAfter { git(work, "switch", "-q", "-c", "feature") }!!
        assertTrue(note, note.contains("- Switched from branch `main` to branch `feature`. HEAD is now at `${short(work)}` “one”."))
    }

    @Test fun reportsAResetBack() {
        commit(work, "two")
        val dropped = short(work)
        val note = noteAfter { git(work, "reset", "-q", "--hard", "HEAD~1") }!!
        assertTrue(note, note.contains("`main` was moved back 1 commit (reset) to `${short(work)}` “one”; no longer on it: `$dropped` “two”."))
    }

    @Test fun reportsAnAmendAsARewrite() {
        commit(work, "two")
        val note = noteAfter { git(work, "commit", "-q", "--amend", "--allow-empty", "-m", "two, better") }!!
        assertTrue(note, note.contains("`main` was rewritten (amend, rebase or reset), replacing 1 earlier commit; its commits since they diverged: `${short(work)}` “two, better”."))
    }

    @Test fun reportsStashing() {
        File(work, "a.txt").writeText("x")
        git(work, "add", "a.txt")
        val stashed = noteAfter { git(work, "stash", "-q") }!!
        assertTrue(stashed, stashed.contains("- Stashed changes (1 stash now)."))
        val popped = noteAfter { git(work, "stash", "pop", "-q") }!!
        assertTrue(popped, popped.contains("- Popped or dropped a stash (0 stashes left)."))
    }

    @Test fun reportsAStashSwappedForAnother() {
        File(work, "a.txt").writeText("x")
        git(work, "add", "a.txt")
        git(work, "stash", "-q")
        val note = noteAfter {
            git(work, "stash", "pop", "-q")
            File(work, "a.txt").writeText("y")
            git(work, "stash", "-q")
        }!!
        assertTrue(note, note.contains("- Stashed and popped or dropped stashes (1 stash now)."))
    }

    @Test fun reportsAnUpstreamRemoved() {
        val note = noteAfter { git(work, "branch", "--unset-upstream") }!!
        assertTrue(note, note.contains("- `main` no longer tracks `origin/main`."))
    }

    @Test fun reportsAMergeInProgressAndItsEnd() {
        File(work, "f.txt").writeText("base\n")
        git(work, "add", "f.txt")
        commit(work, "base")
        git(work, "switch", "-q", "-c", "side")
        File(work, "f.txt").writeText("side\n")
        git(work, "commit", "-q", "-am", "side")
        git(work, "switch", "-q", "main")
        File(work, "f.txt").writeText("main\n")
        git(work, "commit", "-q", "-am", "main")
        val started = noteAfter { runGit(work, "merge", "side") }!!
        assertEquals(GitOperation.MERGE, read().operation)
        assertTrue(started, started.contains("- A merge is in progress (there may be conflicts)."))
        val ended = noteAfter { git(work, "merge", "--abort") }!!
        assertTrue(ended, ended.contains("- A merge finished or was aborted."))
    }

    @Test fun listsAtMostTenCommits() {
        val note = noteAfter { repeat(12) { commit(work, "c$it") } }!!
        assertTrue(note, note.contains("12 new commits on `main`"))
        assertTrue(note, note.contains("“c2”; and 2 more."))
    }

    @Test fun aCheckoutInsideARepositoryIsFoundWithoutGit() {
        assertTrue(insideRepository(File(work, "sub/dir").path))
        assertNotNull(read())
    }
}
