package claudechat.context

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorContextServiceTest : BasePlatformTestCase() {
    private val service get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private lateinit var savedDir: String
    private lateinit var savedSchedule: (Runnable) -> Unit

    // Lines 1-11; JS lists functions, classes, methods, top-level variables and object properties in its Structure View.
    private val js = listOf(
        "function outer(a) {",
        "  const x = 1;",
        "  function inner() {",
        "    return x;",
        "  }",
        "  return inner();",
        "}",
        "class K {",
        "  m() { return 1; }",
        "}",
        "const conf = { a: 1 };",
    ).joinToString("\n", postfix = "\n")

    override fun setUp() {
        super.setUp()
        manager.openFiles.forEach(manager::closeFile) // the light project and its services are shared between tests
        savedDir = service.projectDir
        savedSchedule = service.schedule
        service.projectDir = root
        service.schedule = { it.run() }
    }

    override fun tearDown() {
        try {
            manager.openFiles.forEach(manager::closeFile)
            service.projectDir = savedDir
            service.schedule = savedSchedule
        } finally {
            super.tearDown()
        }
    }

    fun testCaretInANestedFunction() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val context = service.capture()!!
        assertEquals(myFixture.file.virtualFile.path, context.path)
        assertEquals(4, context.line)
        assertEquals(12, context.column)
        assertNull(context.selection)
        assertEquals(Symbol("inner", "function", Lines(3, 5)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretInAClassMethod() {
        myFixture.configureByText("calc.js", js.replace("return 1;", "return <caret>1;"))
        assertEquals(Symbol("m", "method", Lines(9, 9)), service.capture()!!.symbol)
    }

    fun testCaretInAPropertyOutsideAnyFunction() {
        myFixture.configureByText("calc.js", js.replace("a: 1", "a: <caret>1"))
        val context = service.capture()!!
        assertEquals(Symbol("a", "property", Lines(11, 11)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretOutsideEveryElement() {
        myFixture.configureByText("calc.js", "// notes<caret>\n\nfunction f() {}\n")
        assertNull(service.capture()!!.symbol)
    }

    fun testTypeScriptMethod() {
        myFixture.configureByText("c.ts", "export class C {\n  total(n: number): number {\n    return <caret>n;\n  }\n}\n")
        assertEquals(Symbol("total", "method", Lines(2, 4)), service.capture()!!.symbol)
    }

    fun testSelectionLines() {
        myFixture.configureByText("calc.js", js.replace("  const x", "<selection>  const x").replace("return x;", "return x;<caret></selection>"))
        assertEquals(Lines(2, 4), service.capture()!!.selection)
    }

    fun testSelectionEndingAtALineStartStopsOnThePreviousLine() {
        myFixture.configureByText("calc.js", js.replace("  const x = 1;\n", "<selection>  const x = 1;\n<caret></selection>"))
        assertEquals(Lines(2, 2), service.capture()!!.selection)
    }

    fun testPlainTextHasALocationButNoSymbol() {
        myFixture.configureByText("notes.txt", "one\ntw<caret>o\n")
        assertEquals(EditorContext(myFixture.file.virtualFile.path, 2, 3), service.capture())
    }

    fun testNoEditorNoContext() = assertNull(service.capture())

    fun testTheLastFocusedEditorWins() {
        myFixture.configureByText("a.txt", "a")
        val a = myFixture.editor
        manager.openFile(myFixture.addFileToProject("b.txt", "b").virtualFile, false)
        val b = (manager.getSelectedEditor(myFixture.findFileInTempDir("b.txt")) as TextEditor).editor
        service.editorFocused(b)
        assertTrue(service.capture()!!.path.endsWith("/b.txt"))
        service.editorFocused(a)
        assertTrue(service.capture()!!.path.endsWith("/a.txt"))
    }

    fun testEditorsWithoutALocalFileAreIgnored() {
        myFixture.configureByText("a.txt", "a")
        val factory = EditorFactory.getInstance()
        val scratch = factory.createEditor(factory.createDocument("scratch"), project)
        try {
            service.editorFocused(scratch)
            assertTrue(service.capture()!!.path.endsWith("/a.txt"))
        } finally {
            factory.releaseEditor(scratch)
        }
    }

    fun testCaretAndSelectionChangesNotifyListeners() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        var fired = 0
        service.addListener(testRootDisposable) { fired++ }
        myFixture.editor.caretModel.moveToOffset(5)
        assertTrue(fired > 0)
        val afterCaret = fired
        myFixture.editor.selectionModel.setSelection(0, 3)
        assertTrue(fired > afterCaret)
    }

    fun testListenerIsRemovedWithItsParent() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        val parent = Disposer.newDisposable()
        var fired = 0
        service.addListener(parent) { fired++ }
        Disposer.dispose(parent)
        myFixture.editor.caretModel.moveToOffset(5)
        assertEquals(0, fired)
    }

    fun testContextForAChatUsesItsFolderOrTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.js", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.js", "function f() {}\n").virtualFile)
        assertEquals("src/a.js", service.contextFor(root)!!.path)
        assertEquals("src/a.js", service.contextFor("$root/.claude/worktrees/wt1")!!.path)
        assertNull(service.contextFor("$root/.claude/worktrees/wt2"))
        assertNull(service.contextFor("/elsewhere"))
        assertEquals("a.js", service.contextFor("$root/src")!!.path)
    }
}
