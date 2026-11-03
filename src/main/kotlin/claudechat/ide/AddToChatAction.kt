package claudechat.ide

import claudechat.session.Chats
import claudechat.ui.ChatTabs
import claudechat.ui.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefApp

object Mention {
    fun format(path: String, startLine: Int?, endLine: Int?): String = when {
        startLine == null -> "@$path"
        endLine == null || endLine == startLine -> "@$path#L$startLine"
        else -> "@$path#L$startLine-$endLine"
    }

    /** [path] relative to [dir], or null when it is not inside it. */
    fun relativeTo(path: String, dir: String): String? {
        val prefix = dir.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }
}

/** Inserts an @-reference to the current file (and selected lines) into the selected chat tab's input. */
class AddToChatAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val selection = editor.selectionModel
        val lines = if (selection.hasSelection()) {
            val doc = editor.document
            val start = selection.selectionStart
            val end = if (selection.selectionEnd > start) selection.selectionEnd - 1 else start
            doc.getLineNumber(start) + 1 to doc.getLineNumber(end) + 1
        } else {
            null
        }

        TetherTarget.withChat(project) { chat ->
            chat.insert(Mention.format(TetherTarget.pathFor(project, chat, file), lines?.first, lines?.second))
        }
    }
}
