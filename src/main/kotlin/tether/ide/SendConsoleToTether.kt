package tether.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

/** Console popup menu: puts the selected output (or all of it, if nothing is selected) into the Tether message box. */
class SendConsoleToTether : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && editor.document.textLength > 0
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: tailOf(editor.document.charsSequence)
        if (text.isBlank()) return
        TetherTarget.withChat(project) { chat -> chat.insert(TetherMessages.console(text)) }
    }

    /** The end of [console], as much as [TetherMessages.console] sends; a console can hold megabytes. */
    private fun tailOf(console: CharSequence): String =
        console.subSequence((console.length - TetherMessages.MAX_CONSOLE - 1).coerceAtLeast(0), console.length).toString()
}
