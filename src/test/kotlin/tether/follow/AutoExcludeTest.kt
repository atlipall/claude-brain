package tether.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExcludeTest {
    @Test fun platformDefaultExcludesWorktrees() = assertTrue(AutoExclude.excludesWorktrees(".worktrees;.claude/worktrees"))

    @Test fun trailingSlashAndSpacesStillCount() = assertTrue(AutoExclude.excludesWorktrees(" .claude/worktrees/ ;.worktrees"))

    @Test fun otherEntriesDoNotCount() {
        assertFalse(AutoExclude.excludesWorktrees(".worktrees"))
        assertFalse(AutoExclude.excludesWorktrees(""))
        assertFalse(AutoExclude.excludesWorktrees(".claude/worktrees-old"))
    }

    @Test fun withoutWorktreesKeepsTheRest() {
        assertEquals(".worktrees", AutoExclude.withoutWorktrees(".worktrees;.claude/worktrees"))
        assertEquals("", AutoExclude.withoutWorktrees(".claude/worktrees/"))
        assertEquals("a;b", AutoExclude.withoutWorktrees("a;.claude/worktrees;b"))
    }
}
