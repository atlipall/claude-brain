package tether.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.scopeView.ScopeViewPane
import com.intellij.openapi.project.Project
import tether.settings.TetherSettings

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
    /**
     * Shows the named scope [scopeName] if [ProjectViewChoice] allows it. False only when the Scope pane does not (yet)
     * list the scope, so the caller may try again; true when the scope is shown or nothing should change.
     */
    fun show(scopeName: String): Boolean {
        val view = ProjectView.getInstance(project)
        val pane = view.currentProjectViewPane
        // Sub-ids of the Scope pane embed the scope's pattern text, so they are looked up by presentable name.
        val shown = pane?.takeIf { it.id == ScopeViewPane.ID }?.subId?.let(pane::getPresentableSubIdName)
        if (shown == scopeName || !ProjectViewChoice.shouldSwitch(pane?.id, shown)) return true
        val scopes = view.getProjectViewPaneById(ScopeViewPane.ID) ?: return false
        val subId = scopes.subIds.firstOrNull { scopes.getPresentableSubIdName(it) == scopeName } ?: return false
        view.changeView(ScopeViewPane.ID, subId)
        return true
    }
}

/**
 * Find in Files opens on the given named scope. Search Everywhere has no public API for its default scope in build 262,
 * so there the plugin's scopes are only offered in the scope dropdown.
 *
 * The Find default is application-wide, so the user's own one is kept in [TetherSettings] (it survives a crash) and put
 * back when the project that last set a plugin scope stops following or closes, or, after a crash, when the next
 * project starts following. Closing the IDE restores it after settings were saved, so the next run's first follower
 * restores it again. EDT.
 */
object SearchDefaults {
    /** The project whose plugin scope the Find default shows now; null when none in this run of the IDE set one. */
    private var owner: Project? = null

    private val state get() = TetherSettings.getInstance().state

    /** Makes [scopeName] the Find in Files default (preselected in the popup's Scope dropdown) for [project]. */
    fun use(project: Project, scopeName: String) {
        val settings = FindSettings.getInstance()
        if (!state.findScopeSaved) {
            val previous = settings.defaultScopeName
            if (previous?.startsWith(Checkout.SCOPE_PREFIX) != true) {
                state.savedFindScope = previous
                state.findScopeSaved = true
            }
        }
        owner = project
        settings.defaultScopeName = scopeName
        FindManager.getInstance(project).findInProjectModel.apply {
            isProjectScope = false
            moduleName = null
            directoryName = null
            isCustomScope = true
            customScopeName = scopeName
        }
    }

    /**
     * Puts back the user's own Find default if [project] set the plugin scope it shows now, or no project in this run
     * did (a scope left by a crash). A default the user picked since is kept.
     */
    fun restore(project: Project) {
        if (owner != null && owner != project) return
        owner = null
        if (!state.findScopeSaved) return
        val settings = FindSettings.getInstance()
        if (settings.defaultScopeName?.startsWith(Checkout.SCOPE_PREFIX) == true) settings.defaultScopeName = state.savedFindScope
        forgetSaved()
    }

    internal fun forgetSaved() {
        owner = null
        state.findScopeSaved = false
        state.savedFindScope = null
    }
}
