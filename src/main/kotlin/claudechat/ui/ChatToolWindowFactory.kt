package claudechat.ui

import claudechat.follow.CheckoutFollower
import claudechat.session.Chats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("Tether needs JCEF, which is not available in this IDE runtime.")
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, null, false))
            return
        }
        toolWindow.setTitleActions(listOf(NewChatAction(), WorkspaceChatAction()))
        val tabs = ChatTabs(project, toolWindow)
        // Registered before the first tab so selecting it is followed too.
        val follower = CheckoutFollower.getInstance(project)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) follower.follow(event.content.getUserData(ChatTabs.CHAT))
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                // Later: the closed chat leaves Chats when its content is disposed.
                ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed)
            }
        })
        tabs.open(Chats.getInstance(project).mainSpec())
        // Showing the tool window after every tab was closed opens a new main chat (a draft: no process yet).
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == ID && shown.contentManager.contentCount == 0) tabs.open(Chats.getInstance(project).mainSpec())
            }
        })
    }

    companion object {
        const val ID = "Tether"
    }
}
