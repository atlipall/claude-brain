package claudechat.ide.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProblemReportTest {
    private val err = Problem(3, 7, Severity.ERROR, "Type 'string' is not assignable to type 'number'.")
    private val warn = Problem(1, 1, Severity.WARNING, "Unused import")

    @Test fun listsProblemsInPositionOrderWithCounts() = assertEquals(
        """
        src/a.ts: 1 error, 1 warning
          1:1 warning: Unused import
          3:7 error: Type 'string' is not assignable to type 'number'.
        """.trimIndent(),
        ProblemReport.forTool(listOf(FileProblems("src/a.ts", listOf(err, warn)))))

    @Test fun cleanAndSkippedFilesSaySo() = assertEquals(
        "a.ts: no problems\n\nb.png: binary file, not checked",
        ProblemReport.forTool(listOf(FileProblems("a.ts", emptyList()), FileProblems("b.png", emptyList(), "binary file, not checked"))))

    @Test fun multiLineMessagesAreFlattened() = assertEquals(
        "a.ts: 1 error\n  1:1 error: first second",
        ProblemReport.forTool(listOf(FileProblems("a.ts", listOf(Problem(1, 1, Severity.ERROR, "first\n   second"))))))

    @Test fun longListsAreCut() {
        val many = (1..ProblemReport.MAX_PER_FILE + 3).map { Problem(it, 1, Severity.ERROR, "e$it") }
        val text = ProblemReport.forTool(listOf(FileProblems("a.ts", many)))
        assertEquals("  … and 3 more", text.lines().last())
    }

    @Test fun aNoteFollowsTheFilesProblems() = assertEquals(
        "a.ts: no problems\n  (the TypeScript service failed (x); its problems are missing)",
        ProblemReport.forTool(listOf(FileProblems("a.ts", emptyList(), note = "the TypeScript service failed (x); its problems are missing"))))

    @Test fun usageReportSaysWhenItShowsOnlyTheFirst() {
        val file = com.intellij.testFramework.LightVirtualFile("a.ts")
        val found = FoundUsages("f", "function", emptyList(), listOf(Spot(file, 3, "f()")), total = 250)
        assertEquals(
            "function `f` is declared outside the project.\n250 usages (the first 1):\na.ts\n  3: f()",
            UsageReport.format(found) { it.name })
    }

    @Test fun afterEditReportsOnlyErrors() = assertEquals(
        "The IDE reports 1 error in src/a.ts after this edit:\n  3:7 error: Type 'string' is not assignable to type 'number'.\n" + ProblemReport.FIXES_HINT,
        ProblemReport.afterEdit("src/a.ts", listOf(warn, err)))

    @Test fun afterEditIsSilentWithoutErrors() = assertNull(ProblemReport.afterEdit("a.ts", listOf(warn)))

    @Test fun locateFindsWholeWordsOnly() {
        assertEquals(6, Usages.locate("const total = subtotal + tax", "total", null))
        assertNull(Usages.locate("const subtotal = 1", "total", null))
        assertEquals(0, Usages.locate("\$el.x", "\$el", null))
    }

    @Test fun locatePicksTheOccurrenceNearestTheColumn() {
        // "a = a + a": a at indexes 0, 4, 8
        assertEquals(4, Usages.locate("a = a + a", "a", 5))
        assertEquals(8, Usages.locate("a = a + a", "a", 9))
        assertEquals(0, Usages.locate("a = a + a", "a", null))
    }
}
