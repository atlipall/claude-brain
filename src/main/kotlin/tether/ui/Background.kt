package tether.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition

/** Runs [compute] on a pooled thread, then [show] on the EDT unless [expired] (by default: the project has closed). */
internal fun <T> background(project: Project, compute: () -> T, expired: Condition<*> = project.disposed, show: (T) -> Unit) {
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        val result = compute()
        app.invokeLater({ show(result) }, expired)
    }
}
