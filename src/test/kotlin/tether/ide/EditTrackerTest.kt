package tether.ide

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditTrackerTest : BasePlatformTestCase() {
    private val tracker get() = EditTracker.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val worktree get() = "$root/.claude/worktrees/wt1"

    private fun note(vararg files: String) =
        "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\nRe-read them before relying on earlier contents."

    /** Opens [path] (relative to the content root) and types into it without saving. */
    private fun edit(path: String) {
        myFixture.openFileInEditor(myFixture.addFileToProject(path, "x").virtualFile)
        myFixture.type("y")
    }

    /** The light project is shared between tests: saves leftovers first, then starts every key clean. */
    override fun setUp() {
        super.setUp()
        FileDocumentManager.getInstance().saveAllDocuments()
        tracker.register("main")
        tracker.register("wt")
    }

    fun testUnsavedEditIsReportedOnce() {
        edit("a.txt")
        assertEquals(note("a.txt"), tracker.consumeNote("main", root))
        assertNull(tracker.consumeNote("main", root))
    }

    fun testEveryChatWhoseFolderHoldsTheFileIsToldOnce() {
        tracker.register("other")
        edit("b.txt")
        assertEquals(note("b.txt"), tracker.consumeNote("main", root))
        assertEquals(note("b.txt"), tracker.consumeNote("other", root))
        assertNull(tracker.consumeNote("other", root))
    }

    fun testWorktreeFilesGoToTheWorktreeChatOnly() {
        edit(".claude/worktrees/wt1/src/c.txt")
        assertNull(tracker.consumeNote("main", root))
        assertEquals(note("src/c.txt"), tracker.consumeNote("wt", worktree))
    }

    fun testFilesOutsideTheFolderAreNotReported() {
        edit("d.txt")
        assertNull(tracker.consumeNote("wt", worktree))
        assertEquals(note("d.txt"), tracker.consumeNote("main", root))
    }

    fun testRegisteringIgnoresEarlierSaves() {
        edit("e.txt")
        FileDocumentManager.getInstance().saveAllDocuments()
        tracker.register("late")
        assertNull(tracker.consumeNote("late", root))
        assertEquals(note("e.txt"), tracker.consumeNote("main", root))
    }

    fun testExternalChangeIsNotReported() {
        val file = myFixture.addFileToProject("f.txt", "a").virtualFile
        WriteAction.run<Exception> { file.setBinaryContent("b".toByteArray()) }
        assertNull(tracker.consumeNote("main", root))
    }
}
