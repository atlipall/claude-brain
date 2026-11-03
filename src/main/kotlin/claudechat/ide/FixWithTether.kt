package claudechat.ide

import claudechat.ide.tools.Diagnostics
import claudechat.ide.tools.Problem
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Alt+Enter on a highlighted problem: asks the selected Tether chat to fix the problems on the caret's line. */
class FixWithTether : IntentionAction, PriorityAction, DumbAware {
    override fun getText() = "Fix with Tether"
    override fun getFamilyName() = text
    override fun startInWriteAction() = false
    override fun getPriority() = PriorityAction.Priority.LOW
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file?.virtualFile == null) return false
        var found = false
        processLine(project, editor) { found = true; false }
        return found
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val vf = file?.virtualFile ?: return
        val ed = editor ?: return
        val problems = problemsAt(project, ed).ifEmpty { return }
        val line = ed.document.getLineNumber(ed.caretModel.offset) + 1
        TetherTarget.withChat(project) { chat -> chat.send(TetherMessages.fix(TetherTarget.pathFor(project, chat, vf), line, problems)) }
    }

    /** The problems the editor highlights on the caret's line, worst first. */
    private fun problemsAt(project: Project, editor: Editor): List<Problem> {
        val problems = mutableListOf<Problem>()
        processLine(project, editor) { problems += it; true }
        return problems.distinct().sortedBy { it.severity }
    }

    /** Feeds the problems the editor highlights on the caret's line to [process] until it returns false. */
    private fun processLine(project: Project, editor: Editor, process: (Problem) -> Boolean) {
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING, document.getLineStartOffset(line), document.getLineEndOffset(line)) { info ->
            Diagnostics.problemOf(document, info)?.let(process) ?: true
        }
    }
}
