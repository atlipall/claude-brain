package tether.ide.tools

import com.intellij.lang.javascript.inspections.JSUnusedLocalSymbolsInspection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import java.io.File
import java.util.concurrent.Callable

/** [Fixes] and [Refactors] change real files on disk (moves and renames go through the local file system). */
class IdeEditsTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        // The light project's content is in memory; these files are on disk, so make their folder content too.
        PsiTestUtil.addContentRoot(module, myFixture.tempDirFixture.getFile("")!!)
    }

    private fun <T> offEdt(block: () -> T): T {
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching(block) })
        PlatformTestUtil.waitWithEventsDispatching("The IDE tool did not finish", { future.isDone }, 60)
        return future.get().getOrThrow()
    }

    private val root: String get() = myFixture.tempDirFixture.getFile("")!!.path
    private val scope get() = GlobalSearchScope.projectScope(project)
    private fun text(file: VirtualFile) = FileDocumentManager.getInstance().getDocument(file)!!.text
    private fun disk(file: VirtualFile) = VfsUtilCore.loadText(file)

    fun testListsAndAppliesAQuickFix() {
        myFixture.enableInspections(JSUnusedLocalSymbolsInspection())
        val file = myFixture.addFileToProject("unused.js", "function f() {\n  const unused = 1;\n  return 2;\n}\nf();\n").virtualFile
        val fixes = offEdt { Fixes.list(project, file, 2, Deadline(30_000)) }
        val remove = fixes.firstOrNull { it.text.contains("Remove", ignoreCase = true) }
        assertNotNull("expected a remove fix, got ${fixes.map { it.text }}", remove)
        val applied = offEdt { Fixes.apply(project, file, 2, remove!!.text, Deadline(30_000)) }
        assertEquals(listOf(file), applied.changed)
        assertFalse(text(file).contains("unused"))
        assertEquals("the change is saved for Claude to read", text(file), disk(file))
    }

    fun testAnUnknownFixListsTheOffered() {
        myFixture.enableInspections(JSUnusedLocalSymbolsInspection())
        val file = myFixture.addFileToProject("unused2.js", "function f() {\n  const unused = 1;\n  return 2;\n}\nf();\n").virtualFile
        val e = runCatching { offEdt { Fixes.apply(project, file, 2, "Make it pretty", Deadline(30_000)) } }.exceptionOrNull()
        assertTrue("got $e", e is ToolUnavailable && e.message!!.contains("It offers:"))
    }

    fun testLinesWithoutProblemsHaveNoFixes() {
        val file = myFixture.addFileToProject("fine.ts", "export const n = 1;\n").virtualFile
        assertEquals(emptyList<Fix>(), offEdt { Fixes.list(project, file, 1, Deadline(30_000)) })
    }

    fun testRenameUpdatesEveryReference() {
        val lib = myFixture.addFileToProject("lib.ts", "export function greet(name: string) {\n  return name;\n}\n").virtualFile
        val app = myFixture.addFileToProject("app.ts", "import { greet } from './lib';\n\ngreet('x');\n").virtualFile
        val done = offEdt { Refactors.rename(project, SymbolRef(app, 3, "greet", null), "welcome", scope, Deadline(30_000)) }
        assertEquals("function `greet`", done.what)
        assertEquals(setOf(lib, app), done.changed.toSet())
        assertFalse(disk(lib).contains("greet"))
        assertEquals("import { welcome } from './lib';\n\nwelcome('x');\n", disk(app))
    }

    fun testRenameRefusesAnInvalidName() {
        val lib = myFixture.addFileToProject("lib2.ts", "export const a = 1;\n").virtualFile
        val e = runCatching { offEdt { Refactors.rename(project, SymbolRef(lib, 1, "a", null), "not valid", scope, Deadline(30_000)) } }.exceptionOrNull()
        assertTrue("got $e", e is ToolUnavailable && e.message!!.contains("not a valid name"))
    }

    fun testRenameLeavesFilesOutsideTheScopeAlone() {
        val lib = myFixture.addFileToProject("in/lib3.ts", "export function solo() {}\n").virtualFile
        val app = myFixture.addFileToProject("out/app3.ts", "import { solo } from '../in/lib3';\nsolo();\n").virtualFile
        val inside = com.intellij.psi.search.GlobalSearchScopesCore.directoryScope(project, lib.parent, true)
        offEdt { Refactors.rename(project, SymbolRef(lib, 1, "solo", null), "duo", inside, Deadline(30_000)) }
        assertTrue(disk(lib).contains("duo"))
        assertTrue("the other checkout's copy is not touched", disk(app).contains("solo"))
    }

    fun testMoveUpdatesImports() {
        val lib = myFixture.addFileToProject("lib4.ts", "export const four = 4;\n").virtualFile
        val app = myFixture.addFileToProject("app4.ts", "import { four } from './lib4';\nconsole.log(four);\n").virtualFile
        val done = offEdt { Refactors.move(project, lib, "$root/util/numbers.ts", scope, Deadline(30_000)) }
        assertEquals("numbers.ts", done.now.name)
        assertEquals(listOf(app), done.updated)
        assertTrue(File("$root/util/numbers.ts").exists())
        assertFalse(File("$root/lib4.ts").exists())
        assertTrue("import updated: ${disk(app)}", disk(app).contains("./util/numbers"))
    }

    fun testMoveOntoAnExistingFileIsRefused() {
        val lib = myFixture.addFileToProject("lib5.ts", "export const five = 5;\n").virtualFile
        myFixture.addFileToProject("taken.ts", "export {};\n")
        val e = runCatching { offEdt { Refactors.move(project, lib, "$root/taken.ts", scope, Deadline(30_000)) } }.exceptionOrNull()
        assertTrue("got $e", e is ToolUnavailable && e.message!!.contains("already exists"))
        assertTrue(File("$root/lib5.ts").exists())
    }

    fun testMoveAndRenameTogether() {
        val lib = myFixture.addFileToProject("lib6.ts", "export const six = 6;\n").virtualFile
        val app = myFixture.addFileToProject("app6.ts", "import { six } from './lib6';\nconsole.log(six);\n").virtualFile
        offEdt { Refactors.move(project, lib, "$root/nums/sixes.ts", scope, Deadline(30_000)) }
        assertTrue(File("$root/nums/sixes.ts").exists())
        assertTrue("import updated: ${disk(app)}", disk(app).contains("./nums/sixes"))
    }
}
