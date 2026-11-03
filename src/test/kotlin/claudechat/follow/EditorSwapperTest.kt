package claudechat.follow

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorSwapperTest : BasePlatformTestCase() {
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val mapper get() = CheckoutMapper(root)
    private val main get() = mapper.main
    private val wt1 get() = Checkout("wt1", "$root/.claude/worktrees/wt1")
    private val manager get() = FileEditorManager.getInstance(project)

    override fun tearDown() {
        try { manager.openFiles.forEach(manager::closeFile) } finally { super.tearDown() }
    }

    private fun swapper(lookup: (String) -> VirtualFile? = { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }) =
        EditorSwapper(project, mapper, { it.run() }, { it.run() }, lookup)

    private fun file(path: String, text: String = "one\ntwo\nthree\n"): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun openPaths() = manager.openFiles.map { it.path.removePrefix("$root/") }.toSet()

    private fun caret(file: VirtualFile) =
        (manager.getSelectedEditor(file) as TextEditor).editor.caretModel.logicalPosition

    fun testSwapsFilesThatHaveACounterpartAndKeepsTheRest() {
        val a = file("a.txt")
        val onlyMain = file("only-main.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(onlyMain, false)
        manager.openFile(a, true)
        swapper().swap(main, wt1)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt", "only-main.txt"), openPaths())
        assertEquals(".claude/worktrees/wt1/a.txt", manager.selectedFiles.first().path.removePrefix("$root/"))
    }

    fun testCaretIsKeptAndClamped() {
        val a = file("a.txt", "0123456789\n0123456789\n0123456789\n")
        val b = file("b.txt", "0123456789\n0123456789\n")
        file(".claude/worktrees/wt1/a.txt", "0123456789\n0123456789\n0123456789\n")
        file(".claude/worktrees/wt1/b.txt", "x\n")
        manager.openFile(a, false)
        manager.openFile(b, false)
        (manager.getSelectedEditor(a) as TextEditor).editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 4))
        (manager.getSelectedEditor(b) as TextEditor).editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 9))
        swapper().swap(main, wt1)
        assertEquals(LogicalPosition(2, 4), caret(myFixture.findFileInTempDir(".claude/worktrees/wt1/a.txt")))
        assertEquals(LogicalPosition(1, 0), caret(myFixture.findFileInTempDir(".claude/worktrees/wt1/b.txt")))
    }

    fun testUnsavedChangesAreSavedBeforeTheSwap() {
        val a = file("a.txt", "old")
        file(".claude/worktrees/wt1/a.txt")
        myFixture.openFileInEditor(a)
        myFixture.type("new ")
        swapper().swap(main, wt1)
        assertFalse(FileDocumentManager.getInstance().isFileModified(a))
        assertEquals("new old", String(a.contentsToByteArray()))
    }

    fun testSwapsBack() {
        val a = file("a.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(a, true)
        val swapper = swapper()
        swapper.swap(main, wt1)
        swapper.swap(wt1, main)
        assertEquals(setOf("a.txt"), openPaths())
    }

    fun testNothingOpenIsANoOp() {
        swapper().swap(main, wt1)
        assertTrue(manager.openFiles.isEmpty())
    }

    fun testCaretOnATabIndentedLineKeepsItsCharacterColumn() {
        val text = "\t\tfoo\n\t\tbar\n"
        val a = file("a.txt", text)
        file(".claude/worktrees/wt1/a.txt", text)
        manager.openFile(a, true)
        val editor = (manager.getSelectedEditor(a) as TextEditor).editor
        editor.caretModel.moveToOffset(text.indexOf("bar") + 1)
        swapper().swap(main, wt1)
        val moved = (manager.getSelectedEditor(myFixture.findFileInTempDir(".claude/worktrees/wt1/a.txt")) as TextEditor).editor
        assertEquals(text.indexOf("bar") + 1, moved.caretModel.offset)
    }

    fun testCounterpartsAreLookedUpOnlyInTheBackgroundPhase() {
        val a = file("a.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(a, true)
        var inBackground = false
        var lookups = 0
        var lookupsOutsideBackground = 0
        val lookup = { path: String ->
            lookups++
            if (!inBackground) lookupsOutsideBackground++
            myFixture.findFileInTempDir(path.removePrefix("$root/"))
        }
        val queued = mutableListOf<Runnable>()
        EditorSwapper(project, mapper, { queued += Runnable { inBackground = true; try { it.run() } finally { inBackground = false } } }, { it.run() }, lookup)
            .swap(main, wt1)
        assertEquals(0, lookups) // the snapshot phase on the EDT resolves nothing
        queued.single().run()
        assertTrue(lookups > 0)
        assertEquals(0, lookupsOutsideBackground)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
    }

    fun testStaleResultIsDropped() {
        val a = file("a.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(a, true)
        var current = true
        swapper().swap(main, wt1) { current }
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
        manager.closeFile(myFixture.findFileInTempDir(".claude/worktrees/wt1/a.txt"))
        manager.openFile(a, true)
        current = false
        swapper().swap(main, wt1) { current }
        assertEquals(setOf("a.txt"), openPaths())
    }

    fun testRealPooledThreadAndEdtHandOff() {
        val a = file("a.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(a, true)
        var lookupThreadWasEdt = true
        EditorSwapper(project, mapper, lookup = {
            lookupThreadWasEdt = ApplicationManager.getApplication().isDispatchThread
            LocalFileSystem.getInstance().refreshAndFindFileByPath(it)
        }).swap(main, wt1)
        val deadline = System.currentTimeMillis() + 10_000
        while (".claude/worktrees/wt1/a.txt" !in openPaths() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertFalse(lookupThreadWasEdt)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
    }
}
