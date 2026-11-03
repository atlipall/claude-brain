package claudechat.ide

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditTrackerTest : BasePlatformTestCase() {
    private val tracker get() = EditTracker.getInstance(project)

    override fun setUp() {
        super.setUp()
        tracker.consumeNote() // light project is shared between tests: start clean
    }

    fun testUnsavedEditIsReportedOnce() {
        myFixture.configureByText("a.ts", "let x = 1;")
        myFixture.type("2")
        val note = tracker.consumeNote()
        assertEquals(
            "[IDE] Since your last turn the user edited these files: a.ts.\nRe-read them before relying on earlier contents.",
            note)
        assertNull(tracker.consumeNote())
    }

    fun testNoEditsMeansNoNote() {
        myFixture.configureByText("b.ts", "x")
        assertNull(tracker.consumeNote())
    }

    fun testExternalChangeIsNotReported() {
        val file = myFixture.configureByText("c.ts", "a").virtualFile
        WriteAction.run<Exception> { file.setBinaryContent("b".toByteArray()) }
        assertNull(tracker.consumeNote())
    }

    fun testClearDropsRecordedEdits() {
        myFixture.configureByText("d.ts", "x")
        myFixture.type("y")
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        tracker.clear()
        assertNull(tracker.consumeNote())
    }
}
