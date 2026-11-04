package tether.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditNotesTest {
    @Test fun keepsFilesUnderFolderAsRelativePaths() = assertEquals(
        listOf("a.txt", "src/b.txt"),
        EditNotes.underFolder("/p", listOf("/p/a.txt", "/p/src/b.txt", "/q/c.txt", "/pp/d.txt")))

    @Test fun excludesClaudeWorktreesInsideTheFolder() = assertEquals(
        listOf("x.txt"),
        EditNotes.underFolder("/p/", listOf("/p/x.txt", "/p/.claude/worktrees/wt1/y.txt")))

    @Test fun worktreeFolderSeesOnlyItsOwnFiles() = assertEquals(
        listOf("y.txt"),
        EditNotes.underFolder("/p/.claude/worktrees/wt1", listOf("/p/x.txt", "/p/.claude/worktrees/wt1/y.txt", "/p/.claude/worktrees/wt2/z.txt")))

    @Test fun noteText() {
        assertNull(EditNotes.note(emptyList()))
        assertEquals(
            "[IDE] Since your last turn the user edited these files: a.txt, b/c.txt.\nRe-read them before relying on earlier contents.",
            EditNotes.note(listOf("a.txt", "b/c.txt")))
    }
}
