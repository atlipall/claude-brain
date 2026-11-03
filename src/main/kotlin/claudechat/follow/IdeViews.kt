package claudechat.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.scopeView.ScopeViewPane
import com.intellij.openapi.project.Project

object ProjectViewChoice {
    /**
     * Whether following may change the Project tool window: only while it shows the plain Project pane
     * ([ProjectViewPane.ID], "ProjectPane") or one of the plugin's scopes in the Scope pane ([ScopeViewPane.ID], "Scope").
     * [scopeName] is the presentable name of the scope shown, null for other panes.
     */
    fun shouldSwitch(paneId: String?, scopeName: String?): Boolean = when (paneId) {
        ProjectViewPane.ID -> true
        ScopeViewPane.ID -> scopeName?.startsWith(Checkout.SCOPE_PREFIX) == true
        else -> false
    }
}

/** Switches the Project tool window to a plugin scope. EDT only. */
class ProjectViewSwitcher(private val project: Project) {
    /** Shows the named scope [scopeName] if [ProjectViewChoice] allows it; does nothing if the scope pane does not list it. */
    fun show(scopeName: String) {
        val view = ProjectView.getInstance(project)
        val pane = view.currentProjectViewPane
        // Sub-ids of the Scope pane embed the scope's pattern text, so they are looked up by presentable name.
        val shown = pane?.takeIf { it.id == ScopeViewPane.ID }?.subId?.let(pane::getPresentableSubIdName)
        if (shown == scopeName || !ProjectViewChoice.shouldSwitch(pane?.id, shown)) return
        val scopes = view.getProjectViewPaneById(ScopeViewPane.ID) ?: return
        val subId = scopes.subIds.firstOrNull { scopes.getPresentableSubIdName(it) == scopeName } ?: return
        view.changeView(ScopeViewPane.ID, subId)
    }
}

/**
 * Find in Files opens on the given named scope. Search Everywhere has no public API for its default scope in build 262,
 * so there the plugin's scopes are only offered in the scope dropdown.
 */
object SearchDefaults {
    fun use(project: Project, scopeName: String) {
        FindSettings.getInstance().defaultScopeName = scopeName // preselected in the popup's Scope dropdown (application-wide)
        FindManager.getInstance(project).findInProjectModel.apply {
            isProjectScope = false
            moduleName = null
            directoryName = null
            isCustomScope = true
            customScopeName = scopeName
        }
    }
}
