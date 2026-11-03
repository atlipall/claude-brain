package claudechat.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val component = if (JBCefApp.isSupported()) {
            ChatPanel(project, toolWindow.disposable).browser.component
        } else {
            JBLabel("Claude Chat needs JCEF, which is not available in this IDE runtime.")
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(component, null, false))
    }

    companion object {
        const val ID = "Claude Chat"
    }
}
