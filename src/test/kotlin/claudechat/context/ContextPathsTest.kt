package claudechat.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ContextPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val all = { _: String -> true }
    private val none = { _: String -> false }

    private fun forChat(file: String, cwd: String, exists: (String) -> Boolean = all) = ContextPaths.forChat(file, cwd, "/p", exists)

    @Test fun fileInTheChatsFolder() {
        assertEquals("src/a.ts", forChat("/p/src/a.ts", "/p", none))
        assertEquals("lib/b.ts", forChat("/p/.claude/worktrees/wt1/lib/b.ts", "/p/.claude/worktrees/wt1", none))
    }

    @Test fun worktreeChatNamesTheCounterpartOfAMainFile() {
        val asked = mutableListOf<String>()
        assertEquals("src/a.ts", forChat("/p/src/a.ts", "/p/.claude/worktrees/wt1") { asked += it; true })
        assertEquals(listOf("/p/.claude/worktrees/wt1", "/p/.claude/worktrees/wt1/src/a.ts"), asked)
    }

    @Test fun mainChatNamesTheCounterpartOfAWorktreeFile() =
        assertEquals("src/a.ts", forChat("/p/.claude/worktrees/wt1/src/a.ts", "/p"))

    @Test fun worktreeToWorktree() =
        assertEquals("a.ts", forChat("/p/.claude/worktrees/wt2/a.ts", "/p/.claude/worktrees/wt1"))

    @Test fun noCounterpartNoContext() {
        val wt = "/p/.claude/worktrees/wt1"
        val folderOnly = { path: String -> path == wt }
        assertNull(forChat("/p/src/new.ts", wt, folderOnly))
        assertNull(forChat("/p/.claude/worktrees/wt1/new.ts", "/p", none))
    }

    @Test fun newWorktreeChatBeforeItsFolderExistsUsesTheMainRelativePath() {
        val asked = mutableListOf<String>()
        assertEquals("src/a.ts", forChat("/p/src/a.ts", "/p/.claude/worktrees/new1") { asked += it; false })
        assertEquals(listOf("/p/.claude/worktrees/new1"), asked)
        assertNull(forChat("/p/.claude/worktrees/wt2/a.ts", "/p/.claude/worktrees/new1", none))
        assertNull(forChat("/other/a.ts", "/p/.claude/worktrees/new1", none))
    }

    @Test fun filesOutsideTheChatsFolderHaveNoContext() {
        assertNull(forChat("/other/a.ts", "/p"))
        assertNull(forChat("/other/a.ts", "/p/.claude/worktrees/wt1"))
        assertNull(forChat("/p", "/p"))
    }

    @Test fun chatOutsideTheProjectUsesItsOwnFolder() {
        assertEquals("src/a.ts", forChat("/elsewhere/wt/src/a.ts", "/elsewhere/wt"))
        assertNull(forChat("/p/src/a.ts", "/elsewhere/wt"))
    }

    @Test fun pathsAreComparedCanonically() {
        val project = tmp.newFolder("proj")
        File(project, "src").mkdirs()
        File(project, "src/a.ts").writeText("")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), project.toPath())
        assertEquals("src/a.ts", ContextPaths.forChat("${link.path}/src/a.ts", project.path + "/", link.path, all))
    }
}
