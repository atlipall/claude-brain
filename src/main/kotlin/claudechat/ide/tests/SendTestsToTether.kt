package claudechat.ide.tests

import claudechat.ide.FailedTest
import claudechat.ide.TetherMessages
import claudechat.ide.TetherTarget
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Test runner popup menu: puts the failures under the selection into the Tether message box. */
class SendTestsToTether : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // A suite is a defect when any test in it is, so the selection tells without walking the tree.
        e.presentation.isEnabledAndVisible = e.project != null && selected(e).any { it.isDefect }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tests = failed(e).map { FailedTest(it.name, it.locationUrl, it.errorMessage, it.stacktrace) }.ifEmpty { return }
        TetherTarget.withChat(project) { chat -> chat.insert(TetherMessages.failedTests(tests)) }
    }

    /** The failed tests in the selection, suites included as their failed tests. */
    private fun failed(e: AnActionEvent): List<AbstractTestProxy> =
        selected(e).flatMap { it.allTests }.filter { it.isLeaf && it.isDefect }.distinct()

    private fun selected(e: AnActionEvent): List<AbstractTestProxy> =
        e.getData(AbstractTestProxy.DATA_KEYS)?.toList() ?: listOfNotNull(e.getData(AbstractTestProxy.DATA_KEY))
}
