package tether.ide

import tether.ide.tools.Problem
import tether.ide.tools.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToTetherTest {
    @Test fun fixNamesTheProblemAndWhere() = assertEquals(
        "Fix this error at @src/a.ts#L3:\n- error: Cannot find name 'x'.",
        TetherMessages.fix("src/a.ts", 3, listOf(Problem(3, 5, Severity.ERROR, "Cannot find name 'x'."))))

    @Test fun fixListsSeveralProblems() = assertEquals(
        "Fix these problems at @a.ts#L1:\n- error: one two\n- warning: three",
        TetherMessages.fix("a.ts", 1, listOf(Problem(1, 1, Severity.ERROR, "one\n  two"), Problem(1, 4, Severity.WARNING, "three"))))

    @Test fun failedTestsQuoteMessageAndTrace() = assertEquals(
        "This test failed:\n\nadds (file://a.test.ts)\n```\nexpected 2, got 3\nat a.test.ts:4\n```",
        TetherMessages.failedTests(listOf(FailedTest("adds", "file://a.test.ts", "expected 2, got 3", "at a.test.ts:4"))))

    @Test fun aTraceRepeatingTheMessageIsShownOnce() {
        val text = TetherMessages.failedTests(listOf(FailedTest("t", null, "boom", "boom")))
        assertEquals("This test failed:\n\nt\n```\nboom\n```", text)
    }

    @Test fun longTracesAndManyTestsAreCut() {
        val trace = (1..100).joinToString("\n") { "at line$it" }
        val text = TetherMessages.failedTests((1..12).map { FailedTest("t$it", null, null, trace) })
        assertTrue(text.startsWith("These 12 tests failed:"))
        assertTrue(text.endsWith("… and 2 more."))
        assertTrue(!text.contains("at line41"))
    }

    @Test fun consoleOutputIsFencedAndCannotCloseTheFence() {
        val text = TetherMessages.console("ok\n```\nnot code\n")
        assertTrue(text.startsWith("Console output:\n```\n"))
        assertEquals(2, Regex("^```", RegexOption.MULTILINE).findAll(text).count())
    }

    @Test fun longConsoleOutputKeepsTheEnd() {
        val text = TetherMessages.console("x".repeat(30_000) + "THE END")
        assertTrue(text.contains("THE END"))
        assertTrue(text.length < 21_000)
    }
}
