package tether.ui

import tether.follow.CheckoutFollower
import tether.session.ChatSpec
import tether.session.Chats
import tether.session.Transcript
import tether.session.OpenChats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
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
        toolWindow.setTitleActions(listOf(NewChatAction()))
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
        var restoring = true
        restore(project, tabs, toolWindow) { restoring = false }
        // Showing the tool window after every tab was closed starts a fresh main chat.
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == ID && !restoring && shown.contentManager.contentCount == 0) tabs.open(Chats.getInstance(project).mainSpec())
            }
        })
    }

    /**
     * Reopens the chats that were open when the project closed (a fresh main chat when there are none). Their
     * transcripts are read in the background, so a long session doesn't hold up the IDE; then [done] runs and the tabs
     * open. A session the user opened meanwhile is not opened twice.
     */
    private fun restore(project: Project, tabs: ChatTabs, toolWindow: ToolWindow, done: () -> Unit) {
        val chats = Chats.getInstance(project)
        background(project, { readRestorable(chats) }, expired = { project.isDisposed || toolWindow.isDisposed }) { restored ->
            done()
            val open = chats.all().mapNotNull { it.session.sessionId }.toSet()
            for ((spec, resumed) in restored.filter { it.first.resumeId !in open }) {
                try {
                    tabs.open(spec, resumed)
                } catch (e: Exception) {
                    if (e is ControlFlowException) throw e
                    LOG.warn("Couldn't reopen session ${spec.resumeId}", e)
                }
            }
            if (toolWindow.contentManager.contentCount == 0) tabs.open(chats.mainSpec())
            OpenChats.getInstance(project).startTracking() // not before: a save meanwhile would store an empty list
        }
    }

    /** [Chats.restorable], or none (logged) if even the list can't be read. Off the EDT. */
    private fun readRestorable(chats: Chats): List<Pair<ChatSpec, Transcript>> = try {
        chats.restorable()
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        LOG.warn("Couldn't read the sessions to reopen", e)
        emptyList()
    }

    companion object {
        const val ID = "Tether"
        private val LOG = logger<ChatToolWindowFactory>()
    }
}
