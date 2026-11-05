package tether.follow

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSet
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.psi.search.scope.packageSet.ParsingException

/**
 * Keeps one local named scope (`Claude: <name>`, stored in the workspace file, never shared) per followed checkout.
 * Scopes whose name starts with `Claude: ` belong to the plugin; every other local scope is left alone. EDT only.
 */
class CheckoutScopes(private val project: Project) {
    private val holder get() = NamedScopeManager.getInstance(project)

    /**
     * Makes the plugin's scopes exactly [checkouts] (first one wins for a repeated name). The scopes are only written
     * when they changed, as every write makes every scope view rebuild.
     */
    fun sync(checkouts: List<Checkout>) {
        val ours = checkouts.distinctBy { it.scopeName }.mapNotNull { c ->
            compile(ScopePatterns.forCheckout(c))?.let { holder.createScope(c.scopeName, it) }
        }
        val current = holder.editableScopes.toList()
        val next = current.filterNot { it.scopeId.startsWith(Checkout.SCOPE_PREFIX) } + ours
        if (next.map(::key) == current.map(::key)) return
        holder.scopes = next.toTypedArray()
    }

    /** The plugin scope [sync] made for [checkout], or null; only tests look scopes up this way. */
    internal fun find(checkout: Checkout): NamedScope? = holder.getScope(checkout.scopeName)

    private fun key(scope: NamedScope) = scope.scopeId to scope.value?.text

    private fun compile(pattern: String): PackageSet? = try {
        PackageSetFactory.getInstance().compile(pattern)
    } catch (e: ParsingException) {
        LOG.warn("Cannot compile scope pattern $pattern", e)
        null
    }

    companion object {
        private val LOG = logger<CheckoutScopes>()
    }
}
