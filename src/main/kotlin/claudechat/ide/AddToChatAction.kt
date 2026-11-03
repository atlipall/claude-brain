package claudechat.ide

import claudechat.session.SessionManager
import claudechat.ui.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager

object Mention {
    fun format(path: String, startLine: Int?, endLine: Int?): String = when {
        startLine == null -> "@$path"
        endLine == null || endLine == startLine -> "@$path#L$startLine"
        else -> "@$path#L$startLine-$endLine"
    }
}

/** Inserts an @-reference to the current file (and selected lines) into the chat input. */
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
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        val path = root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path

        val selection = editor.selectionModel
        val mention = if (selection.hasSelection()) {
            val doc = editor.document
            val start = selection.selectionStart
            val end = if (selection.selectionEnd > start) selection.selectionEnd - 1 else start
            Mention.format(path, doc.getLineNumber(start) + 1, doc.getLineNumber(end) + 1)
        } else {
            Mention.format(path, null, null)
        }

        val manager = SessionManager.getInstance(project)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID)
        if (toolWindow == null) manager.insert(mention) else toolWindow.activate { manager.insert(mention) }
    }
}
