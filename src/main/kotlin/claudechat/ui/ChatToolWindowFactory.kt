package claudechat.ui

import claudechat.session.Chats
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("Claude Chat needs JCEF, which is not available in this IDE runtime.")
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, null, false))
            return
        }
        toolWindow.setTitleActions(listOf(NewChatAction()))
        val tabs = ChatTabs(project, toolWindow)
        tabs.open(Chats.getInstance(project).mainSpec())
        // Showing the tool window after every tab was closed starts a fresh main chat.
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == ID && shown.contentManager.contentCount == 0) tabs.open(Chats.getInstance(project).mainSpec())
            }
        })
    }

    companion object {
        const val ID = "Claude Chat"
    }
}
