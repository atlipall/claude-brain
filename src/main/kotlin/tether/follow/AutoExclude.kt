package tether.follow

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import java.util.concurrent.ConcurrentHashMap

/**
 * WebStorm 2026.2 excludes `.worktrees` and `.claude/worktrees` from every project (registry key [KEY], read at
 * startup). Excluded files match no named scope, are skipped by Find in Files and lose code insight, so following
 * a worktree only fully works once the user drops `.claude/worktrees` from that list.
 */
object AutoExclude {
    const val KEY = "ide.workspace.model.relative.paths.to.exclude.automatically"
    private const val WORKTREES = CheckoutMapper.WORKTREES
    private val offered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun excludesWorktrees(value: String): Boolean = entries(value).any { it == WORKTREES }

    /** The registry's current value says worktrees are excluded; false when the key does not exist. */
    fun worktreesExcludedNow(): Boolean = try {
        excludesWorktrees(Registry.get(KEY).asString())
    } catch (_: Exception) {
        false
    }

    /** True the first time [key] (a project) asks since the IDE started. */
    internal fun claimOffer(key: String): Boolean = offered.add(key)

    /** [value] without the `.claude/worktrees` entry. */
    fun withoutWorktrees(value: String): String = entries(value).filterNot { it == WORKTREES }.joinToString(";")

    private fun entries(value: String) = value.split(';').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    /** Once per project and IDE session: offers to stop excluding `.claude/worktrees` (needs a restart). */
    fun offerToInclude(project: Project) {
        if (!worktreesExcludedNow() || !claimOffer(project.locationHash)) return
        val registry = Registry.get(KEY)
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Worktrees are excluded from this project",
                "WebStorm excludes .claude/worktrees from indexing and search, so the worktree's Claude scope stays " +
                    "empty and its files have no code insight. Include the worktrees? This needs a restart.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Include and restart") { includeAndRestart(registry) })
            .addAction(NotificationAction.createSimpleExpiring("Not now") {})
            .notify(project)
    }

    /**
     * Drops `.claude/worktrees` from [registry] and restarts the IDE (which asks about running processes first). The
     * restart runs outside the notification action: shutting down from inside it blames this plugin for other plugins'
     * dispose errors.
     */
    private fun includeAndRestart(registry: RegistryValue) {
        registry.setValue(withoutWorktrees(registry.asString()))
        ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().restart() }
    }

    const val NOTIFICATION_GROUP = "Tether"
}
