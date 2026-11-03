package claudechat.follow

import claudechat.session.Chat
import claudechat.session.Chats
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

object Indicator {
    /** The chat header's text: empty when following is off, otherwise `IDE: <checkout>` plus a note for a chat outside this window. */
    fun text(enabled: Boolean, ide: Checkout, chat: Placement, worktreesExcluded: Boolean = false): String = when {
        !enabled -> ""
        !ide.isMain && worktreesExcluded && chat !is Placement.Outside ->
            "IDE: ${ide.name} · worktrees are excluded from indexing (no Project view / Find scope)"
        chat is Placement.Outside ->
            "IDE: ${ide.name} · ${chat.dir} is outside this window; open it as a separate project to follow it"
        else -> "IDE: ${ide.name}"
    }
}

/**
 * Makes the IDE window follow the selected chat's checkout: keeps the plugin's named scopes, swaps open editors,
 * switches the Project view and the Find in Files scope, and tells every chat page what the IDE shows. EDT only.
 */
@Service(Service.Level.PROJECT)
class CheckoutFollower(private val project: Project) {
    private val chats = Chats.getInstance(project)

    /** The main checkout's folder: the project folder (tests point it at the light project's content root). */
    var projectDir: String = chats.projectDir

    private var selected: Chat? = null

    /** The checkout the IDE shows; null means main (nothing followed yet). */
    var current: Checkout? = null
        private set

    /** Whether the IDE excludes `.claude/worktrees` from indexing (tests inject it; the registry is never changed). */
    var excluded: () -> Boolean = AutoExclude::worktreesExcludedNow

    /** Offers to stop excluding worktrees (tests replace it so no notification is shown). */
    var offer: (Project) -> Unit = AutoExclude::offerToInclude

    private val enabled get() = ClaudeChatSettings.getInstance().state.followCheckout

    /** [chat]'s tab was selected: the IDE shows its checkout, and the Project view and Find in Files are set to it again. */
    fun follow(chat: Chat?) {
        selected = chat
        update(selecting = true)
    }

    /**
     * After a chat's folder changed, a chat closed, or the setting changed: keeps the scopes and headers current and
     * follows the selected chat only if its checkout is no longer the one shown.
     */
    fun refresh() = update(selecting = false)

    private fun update(selecting: Boolean) {
        val mapper = CheckoutMapper(projectDir)
        val on = enabled
        if (on) {
            CheckoutScopes(project).sync(listOf(mapper.main) + chats.all().mapNotNull { followable(mapper, it) }.filterNot { it.isMain })
            val target = selected?.let { followable(mapper, it) }
            if (target != null && (selecting || target != (current ?: mapper.main))) show(mapper, target)
        }
        val ide = current ?: mapper.main
        val noScopes = excluded()
        chats.all().forEach { it.showCheckout(Indicator.text(on, ide, mapper.place(it.cwd), noScopes)) }
    }

    private fun show(mapper: CheckoutMapper, target: Checkout) {
        val shown = current ?: mapper.main
        if (target != shown) EditorSwapper(project, mapper).swap(shown, target)
        current = target
        val noScopes = !target.isMain && excluded()
        if (!noScopes) applyViews(target) // excluded files match no scope: the Project view and Find would show nothing
        if (noScopes) offer(project)
    }

    private fun applyViews(target: Checkout) {
        SearchDefaults.use(project, target.scopeName)
        // The Scope pane may learn about a new scope asynchronously: if it does not list it yet, try once more later.
        if (!ProjectViewSwitcher(project).show(target.scopeName)) {
            ApplicationManager.getApplication().invokeLater({
                if (enabled && current == target) ProjectViewSwitcher(project).show(target.scopeName)
            }, project.disposed)
        }
    }

    private fun followable(mapper: CheckoutMapper, chat: Chat): Checkout? = (mapper.place(chat.cwd) as? Placement.Followable)?.checkout

    companion object {
        fun getInstance(project: Project): CheckoutFollower = project.service()
    }
}
