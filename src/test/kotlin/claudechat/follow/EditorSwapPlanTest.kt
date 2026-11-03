package claudechat.follow

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class EditorSwapPlanTest {
    private val mapper = CheckoutMapper("/p")
    private val main = Checkout("main", "/p")
    private val wt1 = Checkout("wt1", "/p/.claude/worktrees/wt1")
    private val wt2 = Checkout("wt2", "/p/.claude/worktrees/wt2")
    @get:Rule val tmp = TemporaryFolder()
    private val all = { _: String -> true }

    @Test fun swapsFilesOfTheOldCheckoutInTabOrder() {
        val open = listOf(OpenFile("/p/b.ts", Caret(3, 4)), OpenFile("/p/src/a.ts", null))
        assertEquals(
            listOf(
                Replacement("/p/b.ts", "/p/.claude/worktrees/wt1/b.ts", Caret(3, 4)),
                Replacement("/p/src/a.ts", "/p/.claude/worktrees/wt1/src/a.ts", null),
            ),
            EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun filesWithoutCounterpartStay() {
        val open = listOf(OpenFile("/p/new.ts", null), OpenFile("/p/old.ts", null))
        val exists = { p: String -> p == "/p/.claude/worktrees/wt1/old.ts" }
        assertEquals(listOf(Replacement("/p/old.ts", "/p/.claude/worktrees/wt1/old.ts", null)),
            EditorSwapPlan.plan(open, main, wt1, mapper, exists))
    }

    @Test fun filesOutsideTheOldCheckoutStay() {
        val open = listOf(OpenFile("/elsewhere/x.ts", null), OpenFile("/p/.claude/worktrees/wt2/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, main, wt1, mapper, all))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, wt1, main, mapper, all))
    }

    @Test fun worktreeToWorktree() {
        val open = listOf(OpenFile("/p/.claude/worktrees/wt1/a.ts", Caret(0, 0)), OpenFile("/p/a.ts", null))
        assertEquals(listOf(Replacement("/p/.claude/worktrees/wt1/a.ts", "/p/.claude/worktrees/wt2/a.ts", Caret(0, 0))),
            EditorSwapPlan.plan(open, wt1, wt2, mapper, all))
    }

    @Test fun counterpartAlreadyOpenIsLeftAlone() {
        val open = listOf(OpenFile("/p/a.ts", null), OpenFile("/p/.claude/worktrees/wt1/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun sameCheckoutPlansNothing() =
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(listOf(OpenFile("/p/a.ts", null)), main, main, mapper, all))

    @Test fun duplicateTabsAreSwappedOnce() {
        val open = listOf(OpenFile("/p/a.ts", Caret(1, 1)), OpenFile("/p/a.ts", Caret(9, 9)))
        assertEquals(listOf(Replacement("/p/a.ts", "/p/.claude/worktrees/wt1/a.ts", Caret(1, 1))),
            EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun caretIsClampedToTheNewDocument() {
        val lengths = listOf(5, 2)
        assertEquals(Caret(1, 2), Caret(7, 9).clamp(2) { lengths[it] })
        assertEquals(Caret(0, 3), Caret(0, 3).clamp(2) { lengths[it] })
        assertEquals(Caret(0, 0), Caret(4, 4).clamp(0) { 0 })
        assertEquals(Caret(0, 0), Caret(-1, -1).clamp(2) { lengths[it] })
    }

    @Test fun caretColumnIsACharacterOffsetOnATabIndentedLine() {
        // "\t\tfoo" has 5 characters; a caret after the tabs is at character 2, whatever the tab width
        assertEquals(Caret(0, 2), Caret(0, 2).clamp(1) { 5 })
        assertEquals(Caret(0, 5), Caret(0, 12).clamp(1) { 5 })
    }

    @Test fun symlinkedSpellingOfAnOpenCounterpartIsStillOpen() {
        val real = tmp.newFolder("real").canonicalFile
        val link = java.io.File(tmp.root, "link").toPath()
        Files.createSymbolicLink(link, real.toPath())
        val realMain = Checkout("main", real.path)
        val realWt = Checkout("wt1", "${real.path}/.claude/worktrees/wt1")
        val realMapper = CheckoutMapper(real.path)
        // main file opened through the link, counterpart opened by its real path: nothing to swap
        val open = listOf(OpenFile("$link/a.ts", null), OpenFile("${real.path}/.claude/worktrees/wt1/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, realMain, realWt, realMapper, all))
        // and the other way round: the counterpart is opened through the link
        val open2 = listOf(OpenFile("${real.path}/a.ts", null), OpenFile("$link/.claude/worktrees/wt1/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open2, realMain, realWt, realMapper, all))
    }
}
