package claudechat.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitWorktreesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = """
        worktree /Users/me/proj
        HEAD 1111111111111111111111111111111111111111
        branch refs/heads/main

        worktree /Users/me/proj/.claude/worktrees/wt-1
        HEAD 2222222222222222222222222222222222222222
        branch refs/heads/worktree-wt-1

        worktree /Users/me/other-checkout
        HEAD 3333333333333333333333333333333333333333
        detached
        locked

    """.trimIndent()

    @Test fun parsesMainLinkedAndDetachedWorktrees() = assertEquals(
        listOf(
            Worktree("/Users/me/proj", "main", isMain = true),
            Worktree("/Users/me/proj/.claude/worktrees/wt-1", "worktree-wt-1", isMain = false),
            Worktree("/Users/me/other-checkout", null, isMain = false),
        ),
        WorktreePorcelain.parse(sample))

    @Test fun bareMainIsDroppedButStillCountsAsFirst() {
        val out = "worktree /r/repo.git\nbare\n\nworktree /r/main\nHEAD abc\nbranch refs/heads/main\n"
        assertEquals(listOf(Worktree("/r/main", "main", isMain = false)), WorktreePorcelain.parse(out))
    }

    @Test fun windowsLineEndingsAndEmptyOutput() {
        assertEquals(listOf(Worktree("/p", "main", true)), WorktreePorcelain.parse("worktree /p\r\nbranch refs/heads/main\r\n\r\n"))
        assertEquals(emptyList<Worktree>(), WorktreePorcelain.parse(""))
    }

    @Test fun nameIsLastPathSegment() = assertEquals("wt-1", Worktree("/p/.claude/worktrees/wt-1/", null, false).name)

    @Test fun listIsNullWhenGitFails() = assertNull(GitWorktrees("/p") { null }.list())

    @Test fun listRunsListerInProjectDir() {
        var asked: File? = null
        val worktrees = GitWorktrees("/Users/me/proj") { asked = it; sample }.list()
        assertEquals(File("/Users/me/proj"), asked)
        assertEquals(3, worktrees!!.size)
    }

    @Test fun knownFoldersAndExistingNamesCombineGitAndClaudeDirs() {
        val proj = tmp.newFolder("proj")
        File(proj, ".claude/worktrees/wt-1").mkdirs()
        File(proj, ".claude/worktrees/wt-2").mkdirs()
        File(proj, ".claude/worktrees/stray.txt").writeText("")
        val git = GitWorktrees(proj.path) { null }
        val listed = listOf(
            Worktree(proj.path, "main", true),
            Worktree("${proj.path}/.claude/worktrees/wt-1", "worktree-wt-1", false),
            Worktree("/elsewhere/co", null, false),
        )
        assertEquals(
            listOf(proj.path, "${proj.path}/.claude/worktrees/wt-1", "/elsewhere/co", "${proj.path}/.claude/worktrees/wt-2"),
            git.knownFolders(listed))
        assertEquals(setOf("wt-1", "co", "wt-2"), git.existingNames(listed))
        assertEquals(
            listOf(proj.path, "${proj.path}/.claude/worktrees/wt-1", "${proj.path}/.claude/worktrees/wt-2"),
            git.knownFolders(null))
    }

    @Test fun knownFoldersWithoutClaudeDir() {
        val proj = tmp.newFolder("plain")
        assertEquals(listOf(proj.path), GitWorktrees(proj.path) { null }.knownFolders(null))
        assertEquals(emptySet<String>(), GitWorktrees(proj.path) { null }.existingNames(null))
    }

    @Test fun realGitReturnsNullOutsideARepository() = assertNull(runGitWorktreeList(tmp.newFolder("not-a-repo")))
}
