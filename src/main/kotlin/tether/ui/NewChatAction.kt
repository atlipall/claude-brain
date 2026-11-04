package tether.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The `+` in the Tether title bar: opens the New-chat popup under the button. */
class NewChatAction : DumbAwareAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tabs = ChatTabs.find(project) ?: return
        val anchor = e.inputEvent?.component
        tabs.newChat { popup ->
            if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
        }
    }
}
