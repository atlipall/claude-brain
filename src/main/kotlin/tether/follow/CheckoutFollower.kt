package tether.follow

import tether.session.Chat
import tether.session.Chats
import tether.settings.TetherSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File

object Indicator {
    /**
     * The chat header's text: `IDE: <checkout>` when the IDE shows a checkout other than the chat's, with a note for a
     * chat outside this window or while worktrees are excluded from indexing. Empty when following is off, or when the
     * IDE shows the chat's own checkout and there is nothing to note: the tab already says which checkout that is.
     */
    fun text(enabled: Boolean, ide: Checkout, chat: Placement, worktreesExcluded: Boolean = false): String = when {
        !enabled -> ""
        !ide.isMain && worktreesExcluded && chat !is Placement.Outside ->
            "IDE: ${ide.name} · worktrees are excluded from indexing (no Project view / Find scope)"
        chat is Placement.Outside ->
            "IDE: ${ide.name} · ${chat.dir} is outside this window; open it as a separate project to follow it"
        chat == Placement.Followable(ide) -> ""
        else -> "IDE: ${ide.name}"
    }
}

/**
 * Makes the IDE window follow the selected chat's checkout: keeps the plugin's named scopes, swaps open editors,
 * switches the Project view and the Find in Files scope, and tells every chat page what the IDE shows. EDT only.
 * Starting puts back a Find default a crash left on a plugin scope; closing puts back the one this project set.
 */
@Service(Service.Level.PROJECT)
class CheckoutFollower(private val project: Project) : Disposable {
    init {
        SearchDefaults.restore(project)
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

    /** Runs the swap's lookup phase off the EDT, and its apply phase on it (tests run both inline). */
    var swapBackground: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) }
    var swapOnEdt: (Runnable) -> Unit = { ApplicationManager.getApplication().invokeLater(it, project.disposed) }

    /** Bumped by every swap request: a swap whose lookup finishes after a newer request is dropped. */
    private var swapRequest = 0

    /** Set while the IDE shows a worktree whose folder was not there yet: the checkout its editors still belong to. */
    private var pendingSwapFrom: Checkout? = null

    /** While a swap is not applied yet: the checkout the editors still show, which the next request swaps from. */
    private var inFlightFrom: Checkout? = null

    /** Whether the Project view and Find default were switched to a checkout of this project. */
    private var viewsApplied = false

    private val enabled get() = TetherSettings.getInstance().state.followCheckout

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

    /**
     * Syncs the scopes (the checkout the IDE shows keeps its scope even when no open chat is in it any more), shows the
     * selected chat's checkout when [selecting] or when it is not the one shown, and tells every chat what the IDE shows.
     */
    private fun update(selecting: Boolean) {
        val mapper = CheckoutMapper(projectDir)
        val on = enabled
        if (on) {
            val wanted = chats.all().mapNotNull { followable(mapper, it) } + listOfNotNull(current)
            CheckoutScopes(project).sync(listOf(mapper.main) + wanted.filterNot { it.isMain })
            val target = selected?.let { followable(mapper, it) }
            if (target != null && (selecting || target != (current ?: mapper.main) || pendingSwapFrom != null || (current?.isMain == false && !viewsApplied))) show(mapper, target)
        } else if (viewsApplied) {
            SearchDefaults.restore(project)
            viewsApplied = false
        }
        val ide = current ?: mapper.main
        val noScopes = excluded()
        chats.all().forEach { it.showCheckout(Indicator.text(on, ide, mapper.place(it.cwd), noScopes)) }
    }

    /**
     * Makes the IDE show [target]: swaps the editors from the checkout they still show (which may be older than
     * [current] while a retry waits or a swap is in flight), then switches the Project view and Find default.
     *
     * `claude --worktree` selects its tab before the folder exists, so a swap to a missing folder waits until
     * [refresh] sees it. A project that never shows a worktree keeps its own Project view and Find scope, and going
     * back to main restores them. While worktrees are excluded from indexing their files match no scope, so the views
     * are left alone (they would show nothing) and the user is offered to include the worktrees instead.
     */
    private fun show(mapper: CheckoutMapper, target: Checkout) {
        val shown = current ?: mapper.main
        val leaveViews = target.isMain && (current == null || current!!.isMain || !viewsApplied)
        if (target != shown) {
            val editorsFrom = pendingSwapFrom ?: inFlightFrom ?: shown
            pendingSwapFrom = null
            if (!target.isMain && !dirExists(target.dir)) pendingSwapFrom = editorsFrom else swap(mapper, editorsFrom, target)
        } else {
            val from = pendingSwapFrom
            if (from != null && dirExists(target.dir)) {
                swap(mapper, from, target)
                pendingSwapFrom = null
            }
        }
        current = target
        val noScopes = !target.isMain && excluded()
        if (!noScopes && !leaveViews) applyViews(target)
        if (noScopes) offer(project)
    }

    /**
     * Swaps editors without blocking the EDT; the result is applied only if no newer swap was requested and following
     * is still on. A request still in flight has not touched the editors: they show its `from`, so the new swap chains
     * from there (A -> B -> C is A -> C), and a swap back to where the editors already are is dropped.
     */
    private fun swap(mapper: CheckoutMapper, from: Checkout, to: Checkout) {
        val request = ++swapRequest
        val shown = inFlightFrom ?: from
        if (shown == to) {
            inFlightFrom = null
            return
        }
        inFlightFrom = shown
        EditorSwapper(project, mapper, swapBackground, swapOnEdt)
            .swap(shown, to, { request == swapRequest && enabled }, { if (request == swapRequest) inFlightFrom = null })
    }

    /**
     * Switches the Find default and the Project view to [target]'s scope. The Scope pane may learn about a new scope
     * asynchronously: if it does not list the scope yet, one more attempt follows later.
     */
    private fun applyViews(target: Checkout) {
        viewsApplied = true
        SearchDefaults.use(project, target.scopeName)
        if (!ProjectViewSwitcher(project).show(target.scopeName)) {
            ApplicationManager.getApplication().invokeLater({
                if (enabled && current == target) ProjectViewSwitcher(project).show(target.scopeName)
            }, project.disposed)
        }
    }

    private fun followable(mapper: CheckoutMapper, chat: Chat): Checkout? = (mapper.place(chat.cwd) as? Placement.Followable)?.checkout

    override fun dispose() {
        SearchDefaults.restore(project)
    }

    companion object {
        fun getInstance(project: Project): CheckoutFollower = project.service()
    }
}
