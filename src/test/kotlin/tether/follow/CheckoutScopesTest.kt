package tether.follow

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSetBase
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CheckoutScopesTest : BasePlatformTestCase() {
    private val holder get() = NamedScopeManager.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val main get() = Checkout("main", root)
    private val wt1 get() = Checkout("wt1", "$root/.claude/worktrees/wt1")
    private val wt2 get() = Checkout("wt2", "$root/.claude/worktrees/wt2")

    override fun setUp() {
        super.setUp()
        holder.scopes = emptyArray() // the light project is shared between tests
    }

    override fun tearDown() {
        try { holder.scopes = emptyArray() } finally { super.tearDown() }
    }

    private fun names() = holder.editableScopes.map { it.scopeId }

    private fun matches(pattern: String, vararg paths: String): List<String> {
        val set = PackageSetFactory.getInstance().compile(pattern) as PackageSetBase
        return paths.filter { set.contains(myFixture.findFileInTempDir(it), project, holder) }
    }

    fun testSyncCreatesOneScopePerCheckout() {
        CheckoutScopes(project).sync(listOf(main, wt1, wt1))
        assertEquals(listOf("Claude: main", "Claude: wt1"), names())
        assertEquals(ScopePatterns.MAIN, holder.getScope("Claude: main")!!.value!!.text)
        assertEquals("file:.claude/worktrees/wt1//*", CheckoutScopes(project).find(wt1)!!.value!!.text)
    }

    fun testSyncRemovesScopesOfCheckoutsNoLongerListed() {
        val scopes = CheckoutScopes(project)
        scopes.sync(listOf(main, wt1, wt2))
        scopes.sync(listOf(main, wt2))
        assertEquals(listOf("Claude: main", "Claude: wt2"), names())
        assertNull(scopes.find(wt1))
    }

    fun testUserScopesAreKept() {
        holder.scopes = arrayOf(holder.createScope("Mine", PackageSetFactory.getInstance().compile("file:src//*")))
        CheckoutScopes(project).sync(listOf(main))
        assertEquals(listOf("Mine", "Claude: main"), names())
        CheckoutScopes(project).sync(emptyList())
        assertEquals(listOf("Mine"), names())
    }

    fun testUnchangedSyncDoesNotReplaceTheScopes() {
        val scopes = CheckoutScopes(project)
        scopes.sync(listOf(main, wt1))
        val before = holder.editableScopes.toList()
        scopes.sync(listOf(main, wt1))
        assertSame(before[0], holder.editableScopes[0])
    }

    fun testMainPatternMatchesTopLevelAndNestedFiles() {
        listOf("a.txt", "dir/b.txt", ".claude/settings.txt").forEach { myFixture.addFileToProject(it, "x") }
        assertEquals(listOf("a.txt", "dir/b.txt", ".claude/settings.txt"),
            matches(ScopePatterns.MAIN, "a.txt", "dir/b.txt", ".claude/settings.txt"))
    }

    // The platform excludes .claude/worktrees from the project by default (see AutoExclude), so pattern semantics
    // for a worktree folder are checked on an ordinary folder.
    fun testFolderPatternMatchesOnlyThatFolder() {
        listOf("wts/wt1/a.txt", "wts/wt1/d/e/f.txt", "wts/wt10/a.txt", "wts/wt-1.x/a.txt").forEach { myFixture.addFileToProject(it, "x") }
        assertEquals(listOf("wts/wt1/a.txt", "wts/wt1/d/e/f.txt"),
            matches(ScopePatterns.under("wts/wt1"), "wts/wt1/a.txt", "wts/wt1/d/e/f.txt", "wts/wt10/a.txt", "wts/wt-1.x/a.txt"))
        assertEquals(listOf("wts/wt-1.x/a.txt"), matches(ScopePatterns.under("wts/wt-1.x"), "wts/wt1/a.txt", "wts/wt-1.x/a.txt"))
    }
}
