package tether.ui

import tether.follow.AutoExclude
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition

/**
 * Runs [compute] on a pooled thread, then [show] on the EDT unless [expired] (by default: the project has closed). When
 * [compute] fails, the failure is logged and, unless [expired], shown as a notification titled [failed] (what couldn't
 * be done, e.g. "Couldn't list past sessions"), so the click that started it doesn't silently do nothing.
 */
internal fun <T> background(
    project: Project,
    compute: () -> T,
    expired: Condition<*> = project.disposed,
    failed: String = "Tether ran into an error",
    show: (T) -> Unit,
) {
    val app = ApplicationManager.getApplication()
    app.executeOnPooledThread {
        val result = try {
            compute()
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            LOG.warn(failed, e)
            app.invokeLater({ notifyFailure(project, failed, e) }, expired)
            return@executeOnPooledThread
        }
        app.invokeLater({ show(result) }, expired)
    }
}

private fun notifyFailure(project: Project, title: String, e: Throwable) {
    NotificationGroupManager.getInstance().getNotificationGroup(AutoExclude.NOTIFICATION_GROUP)
        .createNotification(title, e.message ?: e.javaClass.simpleName, NotificationType.ERROR)
        .notify(project)
}

private val LOG = Logger.getInstance("#tether.ui.Background")
