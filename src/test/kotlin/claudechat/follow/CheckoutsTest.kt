package claudechat.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CheckoutsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mapper = CheckoutMapper("/p")
    private val main = Checkout("main", "/p")
    private val wt1 = Checkout("wt1", "/p/.claude/worktrees/wt1")
    private val wt2 = Checkout("wt2", "/p/.claude/worktrees/wt2")

    @Test fun projectFolderIsMain() {
        assertEquals(Placement.Followable(main), mapper.place("/p/"))
        assertTrue(mapper.main.isMain)
        assertEquals("Claude: main", mapper.main.scopeName)
    }

    @Test fun worktreeFolderIsFollowable() {
        assertEquals(Placement.Followable(wt1), mapper.place("/p/.claude/worktrees/wt1"))
        assertEquals("Claude: wt1", wt1.scopeName)
    }

    @Test fun otherFoldersAreOutside() {
        assertEquals(Placement.Outside("/elsewhere/wt1"), mapper.place("/elsewhere/wt1"))
        assertEquals(Placement.Outside("/p/src"), mapper.place("/p/src"))
        assertEquals(Placement.Outside("/p/.claude/worktrees"), mapper.place("/p/.claude/worktrees"))
        assertEquals(Placement.Outside("/p/.claude/worktrees/wt1/sub"), mapper.place("/p/.claude/worktrees/wt1/sub"))
        assertEquals(Placement.Outside("/p/.claude/worktrees/has space"), mapper.place("/p/.claude/worktrees/has space"))
    }

    @Test fun worktreeNamedMainIsOutsideNotMain() {
        assertEquals(Placement.Outside("/p/.claude/worktrees/main"), mapper.place("/p/.claude/worktrees/main"))
        assertEquals(Placement.Outside("/p/.claude/worktrees/MAIN"), mapper.place("/p/.claude/worktrees/MAIN"))
    }

    @Test fun placementComparesCanonicalPaths() {
        val project = tmp.newFolder("proj")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), project.toPath())
        File(project, ".claude/worktrees/wt1").mkdirs()
        val viaLink = CheckoutMapper(link.path)
        assertEquals(Placement.Followable(viaLink.main), viaLink.place(project.path))
        val placed = viaLink.place("${project.path}/.claude/worktrees/wt1") as Placement.Followable
        assertEquals("wt1", placed.checkout.name)
        assertEquals(File(project, ".claude/worktrees/wt1").canonicalPath, placed.checkout.dir)
    }

    @Test fun counterpartKeepsTheRelativePath() {
        assertEquals("/p/.claude/worktrees/wt1/src/a.ts", mapper.counterpart("/p/src/a.ts", main, wt1))
        assertEquals("/p/src/a.ts", mapper.counterpart("/p/.claude/worktrees/wt1/src/a.ts", wt1, main))
        assertEquals("/p/.claude/worktrees/wt2/a.ts", mapper.counterpart("/p/.claude/worktrees/wt1/a.ts", wt1, wt2))
    }

    @Test fun filesOutsideTheSourceCheckoutHaveNoCounterpart() {
        assertNull(mapper.counterpart("/other/a.ts", main, wt1))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt2/a.ts", wt1, main))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt1/a.ts", main, wt2))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt10/a.ts", wt1, main))
        assertNull(mapper.counterpart("/p", main, wt1))
    }

    @Test fun scopePatterns() {
        assertEquals("(file:*||file:*//*)&&!file:.claude/worktrees//*", ScopePatterns.forCheckout(main))
        assertEquals("file:.claude/worktrees/wt1//*", ScopePatterns.forCheckout(wt1))
        assertEquals("file:wts/a//*", ScopePatterns.under("/wts/a/"))
    }

    @Test fun checkoutOfAFile() {
        assertEquals(main, mapper.checkoutOf("/p/src/a.ts"))
        assertEquals(wt1, mapper.checkoutOf("/p/.claude/worktrees/wt1/src/a.ts"))
        assertNull(mapper.checkoutOf("/p/.claude/worktrees/a.ts"))
        assertNull(mapper.checkoutOf("/p/.claude/worktrees/main/a.ts"))
        assertNull(mapper.checkoutOf("/other/a.ts"))
        assertNull(mapper.checkoutOf("/p"))
    }
}
