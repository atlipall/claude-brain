package claudechat.ide.tools

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture

/** [Diagnostics] and [Usages] against the IDE's real analysis (WebStorm's JavaScript and TypeScript support). */
class IdeToolsTest : BasePlatformTestCase() {
    /** Runs [block] on a pooled thread, as the session does, while the EDT keeps dispatching (the tools call into it). */
    private fun <T> offEdt(block: () -> T): T {
        // The pooled thread's exceptions would be logged (failing the test), so they travel back as a Result.
        val future = ApplicationManager.getApplication().executeOnPooledThread(Callable { runCatching(block) })
        PlatformTestUtil.waitWithEventsDispatching("The IDE tool did not finish", { future.isDone }, 60)
        return future.get().getOrThrow()
    }

    fun testDiagnosticsFindsAJavaScriptSyntaxError() {
        val file = myFixture.addFileToProject("broken.js", "function f( {\n  return 1;\n}\n").virtualFile
        val problems = offEdt { Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(30_000)) }[file]?.problems.orEmpty()
        assertTrue("expected a syntax error, got $problems", problems.any { it.severity == Severity.ERROR })
    }

    // The TypeScript service doesn't run in tests (TypeScriptService.getForFile is null here), so its source is faked.
    fun testProblemSourcesAreMergedFilteredAndDeduplicated() {
        val file = myFixture.addFileToProject("typed.ts", "const ok: number = 1;\nconst n: number = \"text\";\n").virtualFile
        val typeError = Problem(2, 7, Severity.ERROR, "Type 'string' is not assignable to type 'number'.")
        val fake = object : ProblemSource {
            override val name = "the fake service"
            override fun start(project: Project, file: VirtualFile) =
                CompletableFuture.completedFuture(listOf(typeError, typeError, Problem(1, 7, Severity.WARNING, "Unused constant")))
        }
        ProblemSource.EP.point.registerExtension(fake, testRootDisposable)
        val errors = offEdt { Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(30_000)) }[file]?.problems.orEmpty()
        assertEquals(listOf(typeError), errors)
        val all = offEdt { Diagnostics.collect(project, listOf(file), HighlightSeverity.WEAK_WARNING, Deadline(30_000)) }[file]?.problems.orEmpty()
        assertTrue("the warning is listed when warnings are asked for: $all", all.any { it.message == "Unused constant" })
    }

    fun testDiagnosticsOfACleanFileIsEmpty() {
        val file = myFixture.addFileToProject("clean.ts", "export const n: number = 1;\n").virtualFile
        val problems = offEdt { Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(30_000)) }[file]?.problems.orEmpty()
        assertEquals(emptyList<Problem>(), problems)
    }

    fun testFindUsagesFollowsImportsAcrossFiles() {
        val a = myFixture.addFileToProject("lib.ts", "export function greet(name: string) {\n  return name;\n}\nconst greeting = 1;\n").virtualFile
        val b = myFixture.addFileToProject("app.ts", "import { greet } from './lib';\n\ngreet('x');\ngreet('y');\n").virtualFile
        val found = offEdt { Usages.find(project, b, 3, "greet", null, GlobalSearchScope.projectScope(project), Deadline(30_000)) }
        assertEquals("greet", found.name)
        assertEquals(listOf(a to 1), found.definitions.map { it.file to it.line })
        val lines = found.usages.filter { it.file == b }.map { it.line }
        assertTrue("expected the calls on lines 3 and 4, got ${found.usages}", lines.containsAll(listOf(3, 4)))
        assertTrue("same-prefix names are not usages", found.usages.none { it.text.contains("greeting") })
    }

    fun testFindUsagesFromTheDeclarationItself() {
        val a = myFixture.addFileToProject("lib2.ts", "export function wave() {}\n").virtualFile
        val b = myFixture.addFileToProject("app2.ts", "import { wave } from './lib2';\nwave();\n").virtualFile
        val found = offEdt { Usages.find(project, a, 1, "wave", null, GlobalSearchScope.projectScope(project), Deadline(30_000)) }
        assertTrue("expected the call in app2.ts, got ${found.usages}", found.usages.any { it.file == b && it.line == 2 })
    }

    fun testAFailingSourceKeepsTheOtherProblemsAndSaysWhatIsMissing() {
        val file = myFixture.addFileToProject("broken2.js", "function f( {\n}\n").virtualFile
        val failing = object : ProblemSource {
            override val name = "the fake service"
            override fun start(project: Project, file: VirtualFile) =
                CompletableFuture.failedFuture<List<Problem>>(IllegalStateException("down"))
        }
        ProblemSource.EP.point.registerExtension(failing, testRootDisposable)
        val checked = offEdt { Diagnostics.collect(project, listOf(file), HighlightSeverity.ERROR, Deadline(30_000)) }[file]!!
        assertTrue("the syntax errors survive: $checked", checked.problems.isNotEmpty())
        assertTrue(checked.note!!.contains("the fake service failed (down)"))
    }

    fun testFindUsagesStaysInTheScope() {
        val a = myFixture.addFileToProject("in/lib4.ts", "export function solo() {}\n").virtualFile
        myFixture.addFileToProject("out/app4.ts", "import { solo } from '../in/lib4';\nsolo();\n")
        val inside = GlobalSearchScopesCore.directoryScope(project, a.parent, true)
        val found = offEdt { Usages.find(project, a, 1, "solo", null, inside, Deadline(30_000)) }
        assertEquals(emptyList<Spot>(), found.usages)
    }

    fun testFindUsagesExplainsAMissingSymbol() {
        val a = myFixture.addFileToProject("lib3.ts", "export const x = 1;\n").virtualFile
        val e = runCatching { offEdt { Usages.find(project, a, 1, "nope", null, GlobalSearchScope.projectScope(project), Deadline(30_000)) } }.exceptionOrNull()
        assertTrue("got $e", e is ToolUnavailable && e.message!!.contains("not on line 1"))
    }
}
