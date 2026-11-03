package claudechat.follow

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebStorm 2026.2 excludes `.worktrees` and `.claude/worktrees` from every project (registry key [KEY], read at
 * startup). Excluded files match no named scope, are skipped by Find in Files and lose code insight, so following
 * a worktree only fully works once the user drops `.claude/worktrees` from that list.
 */
object AutoExclude {
    const val KEY = "ide.workspace.model.relative.paths.to.exclude.automatically"
    private const val WORKTREES = CheckoutMapper.WORKTREES
    private val offered = AtomicBoolean()

    fun excludesWorktrees(value: String): Boolean = entries(value).any { it == WORKTREES }

    /** [value] without the `.claude/worktrees` entry. */
    fun withoutWorktrees(value: String): String = entries(value).filterNot { it == WORKTREES }.joinToString(";")

    private fun entries(value: String) = value.split(';').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    /** Once per IDE session: offers to stop excluding `.claude/worktrees` (needs a restart). */
    fun offerToInclude(project: Project) {
        val registry = Registry.get(KEY)
        if (!excludesWorktrees(registry.asString()) || !offered.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Worktrees are excluded from this project",
                "WebStorm excludes .claude/worktrees from indexing and search, so the worktree's Claude scope stays " +
                    "empty and its files have no code insight. Include the worktrees? This needs a restart.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Include and restart") {
                registry.setValue(withoutWorktrees(registry.asString()))
                ApplicationManager.getApplication().restart()
            })
            .addAction(NotificationAction.createSimpleExpiring("Not now") {})
            .notify(project)
    }

    const val NOTIFICATION_GROUP = "Claude Chat"
}
