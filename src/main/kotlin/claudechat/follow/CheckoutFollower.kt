package claudechat.follow

import claudechat.session.Chat
import claudechat.session.Chats
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
class CheckoutFollower(private val project: Project) : Disposable {
    init {
        live.incrementAndGet()
    }

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

    /** Whether a checkout's folder exists on disk (tests inject it, as their files live in an in-memory file system). */
    var dirExists: (String) -> Boolean = { File(it).isDirectory }

    /** Set while the IDE shows a worktree whose folder was not there yet: the checkout its editors still belong to. */
    private var pendingSwapFrom: Checkout? = null

    /** Whether the Project view and Find default were switched to a checkout of this project. */
    private var viewsApplied = false

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
            // The checkout the IDE shows keeps its scope even when no open chat is in it any more.
            val wanted = chats.all().mapNotNull { followable(mapper, it) } + listOfNotNull(current)
            CheckoutScopes(project).sync(listOf(mapper.main) + wanted.filterNot { it.isMain })
            val target = selected?.let { followable(mapper, it) }
            if (target != null && (selecting || target != (current ?: mapper.main) || pendingSwapFrom != null || (current?.isMain == false && !viewsApplied))) show(mapper, target)
        } else if (viewsApplied) {
            SearchDefaults.restore() // following stopped: the user's own Find default comes back
            viewsApplied = false
        }
        val ide = current ?: mapper.main
        val noScopes = excluded()
        chats.all().forEach { it.showCheckout(Indicator.text(on, ide, mapper.place(it.cwd), noScopes)) }
    }

    private fun show(mapper: CheckoutMapper, target: Checkout) {
        val shown = current ?: mapper.main
        // A project that never shows a worktree keeps its own Project view and Find scope; going back to main restores them.
        val leaveViews = target.isMain && (current == null || current!!.isMain || !viewsApplied)
        if (target != shown) {
            // `claude --worktree` selects its tab before the folder exists: swap once refresh() sees the folder.
            pendingSwapFrom = null
            if (!target.isMain && !dirExists(target.dir)) pendingSwapFrom = shown else EditorSwapper(project, mapper).swap(shown, target)
        } else {
            val from = pendingSwapFrom
            if (from != null && dirExists(target.dir)) {
                EditorSwapper(project, mapper).swap(from, target)
                pendingSwapFrom = null
            }
        }
        current = target
        val noScopes = !target.isMain && excluded()
        if (!noScopes && !leaveViews) applyViews(target) // excluded files match no scope: the Project view and Find would show nothing
        if (noScopes) offer(project)
    }

    private fun applyViews(target: Checkout) {
        viewsApplied = true
        SearchDefaults.use(project, target.scopeName)
        // The Scope pane may learn about a new scope asynchronously: if it does not list it yet, try once more later.
        if (!ProjectViewSwitcher(project).show(target.scopeName)) {
            ApplicationManager.getApplication().invokeLater({
                if (enabled && current == target) ProjectViewSwitcher(project).show(target.scopeName)
            }, project.disposed)
        }
    }

    private fun followable(mapper: CheckoutMapper, chat: Chat): Checkout? = (mapper.place(chat.cwd) as? Placement.Followable)?.checkout

    override fun dispose() {
        if (live.decrementAndGet() <= 0) SearchDefaults.restore()
    }

    companion object {
        private val live = AtomicInteger()

        fun getInstance(project: Project): CheckoutFollower = project.service()
    }
}
