package tether.session

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/** Messages the user sent in this project, oldest first, shared by every chat tab and kept across restarts. */
@Service(Service.Level.PROJECT)
// Named from before the rename to Tether; changing it would drop saved prompt history.
@State(name = "ClaudeChatPromptHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PromptHistory : SimplePersistentStateComponent<PromptHistory.State>(State()) {
    class State : BaseState() {
        var entries by list<String>()
    }

    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()

    fun entries(): List<String> = synchronized(this) { state.entries.toList() }

    /** Appends [text] (see [append]) and tells subscribers when the list changed. */
    fun add(text: String) {
        val updated = synchronized(this) {
            val current = state.entries.toList()
            val next = append(current, text)
            if (next == current) return
            state.entries = next.toMutableList()
            next
        }
        listeners.forEach { it(updated) }
    }

    fun clear() {
        synchronized(this) { state.entries = mutableListOf() }
        listeners.forEach { it(emptyList()) }
    }

    /** [listener] gets the full list after every change, on the caller's thread. Returns the unsubscribe function. */
    fun subscribe(listener: (List<String>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    companion object {
        const val CAP = 100

        /** Most recent last; a repeat of the last entry and blank text are not added; only the newest [cap] are kept. */
        fun append(entries: List<String>, text: String, cap: Int = CAP): List<String> {
            if (text.isBlank() || entries.lastOrNull() == text) return entries
            return (entries + text).takeLast(cap)
        }

        fun getInstance(project: Project): PromptHistory = project.service()
    }
}
