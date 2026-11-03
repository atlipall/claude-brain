package claudechat.follow

import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.settings.ClaudeChatSettings
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.find.FindSettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CheckoutFollowerTest : BasePlatformTestCase() {
    private val connections = mutableListOf<FakeConnection>()
    private val chats get() = Chats.getInstance(project)
    private val holder get() = NamedScopeManager.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private val settings get() = ClaudeChatSettings.getInstance().state
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private var savedScopeName: String? = null
    private var savedFollow = true
    private var offers = 0
    private lateinit var follower: CheckoutFollower

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        connections.clear()
        chats.connector = { FakeConnection().also { connections += it } }
        holder.scopes = emptyArray()
        SearchDefaults.forgetSaved()
        savedScopeName = FindSettings.getInstance().defaultScopeName
        savedFollow = settings.followCheckout
        settings.followCheckout = true
        follower = CheckoutFollower(project).also { it.projectDir = root; it.excluded = { false }; it.dirExists = ::dirExists; it.offer = { offers++ }; it.swapBackground = { r -> r.run() }; it.swapOnEdt = { r -> r.run() } } // a fresh follower: IDE shows main
    }

    override fun tearDown() {
        try {
            manager.openFiles.forEach(manager::closeFile)
            chats.closeAll()
            holder.scopes = emptyArray()
            FindSettings.getInstance().defaultScopeName = savedScopeName
            settings.followCheckout = savedFollow
        } finally {
            super.tearDown()
        }
    }

    private fun dirExists(path: String) =
        ProjectRootManager.getInstance(project).contentRoots.first().fileSystem.findFileByPath(path)?.isDirectory == true

    private fun openPaths() = manager.openFiles.map { it.path.removePrefix("$root/") }.toSet()
    private fun scopeNames() = holder.editableScopes.map { it.scopeId }

    private fun openMainFileWithCounterpart() {
        manager.openFile(myFixture.addFileToProject("a.txt", "x").virtualFile, true)
        myFixture.addFileToProject(".claude/worktrees/wt1/a.txt", "x")
    }

    fun testSelectingAWorktreeChatFollowsIt() {
        openMainFileWithCounterpart()
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertEquals("wt1", follower.current?.name)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
        assertEquals(listOf("Claude: main", "Claude: wt1"), scopeNames())
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        assertEquals("IDE: wt1", wt.checkoutText)
        assertEquals("IDE: wt1", main.checkoutText)
    }

    fun testSelectingMainAgainSwapsBack() {
        openMainFileWithCounterpart()
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        follower.follow(main)
        assertEquals("main", follower.current?.name)
        assertEquals(setOf("a.txt"), openPaths())
        assertEquals("Claude: main", FindSettings.getInstance().defaultScopeName)
        assertEquals("IDE: main", main.checkoutText)
    }

    fun testChatOutsideTheProjectChangesNothingAndSaysWhy() {
        openMainFileWithCounterpart()
        val outside = chats.open(ChatSpec("x", "/elsewhere/x"))
        follower.follow(outside)
        assertNull(follower.current)
        assertEquals(setOf("a.txt"), openPaths())
        assertEquals("IDE: main · /elsewhere/x is outside this window; open it as a separate project to follow it", outside.checkoutText)
    }

    fun testDisabledFollowingChangesNothing() {
        settings.followCheckout = false
        openMainFileWithCounterpart()
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertNull(follower.current)
        assertEquals(setOf("a.txt"), openPaths())
        assertTrue(scopeNames().isEmpty())
        assertEquals("", wt.checkoutText)
    }

    fun testClosedChatsLoseTheirScope() {
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(main)
        assertEquals(listOf("Claude: main", "Claude: wt1"), scopeNames())
        chats.close(wt)
        follower.refresh()
        assertEquals(listOf("Claude: main"), scopeNames())
    }

    fun testSelectedChatMovingOutsideIsReflected() {
        val wt = chats.open(ChatSpec.newWorktree(root, "wt2"))
        follower.follow(wt)
        assertEquals("IDE: wt2", wt.checkoutText)
        connections.single().emit(Fx.init(cwd = "/elsewhere/wt2"))
        follower.refresh()
        assertEquals(listOf("Claude: main", "Claude: wt2"), scopeNames()) // still the checkout the IDE shows
        assertTrue(wt.checkoutText, wt.checkoutText.startsWith("IDE: wt2 · /elsewhere/wt2 is outside this window"))
    }

    fun testTurningFollowingOnFollowsTheSelectedChatAtOnce() {
        openMainFileWithCounterpart()
        settings.followCheckout = false
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertNull(follower.current)
        settings.followCheckout = true
        follower.refresh()
        assertEquals("wt1", follower.current?.name)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
        assertEquals("IDE: wt1", wt.checkoutText)
    }

    fun testIndicatorText() {
        val main = Checkout("main", "/p")
        assertEquals("", Indicator.text(false, main, Placement.Followable(main)))
        assertEquals("IDE: main", Indicator.text(true, main, Placement.Followable(main)))
        assertEquals("IDE: main · /x is outside this window; open it as a separate project to follow it",
            Indicator.text(true, main, Placement.Outside("/x")))
    }

    fun testExcludedWorktreeSwapsEditorsButLeavesViewAndFindAlone() {
        openMainFileWithCounterpart()
        FindSettings.getInstance().defaultScopeName = "Mine"
        follower.excluded = { true }
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertEquals("wt1", follower.current?.name)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
        assertEquals("Mine", FindSettings.getInstance().defaultScopeName)
        assertEquals("IDE: wt1 · worktrees are excluded from indexing (no Project view / Find scope)", wt.checkoutText)
        assertEquals(1, offers)
    }

    fun testNotExcludedWorktreeSetsFindAndOffersNothing() {
        openMainFileWithCounterpart()
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        assertEquals("IDE: wt1", wt.checkoutText)
        assertEquals(0, offers)
    }

    fun testOfferIsClaimedOncePerProject() {
        assertTrue(AutoExclude.claimOffer("test-a"))
        assertFalse(AutoExclude.claimOffer("test-a"))
        assertTrue(AutoExclude.claimOffer("test-b"))
    }

    fun testNewWorktreeSwapsEditorsOnceItsFolderExists() {
        manager.openFile(myFixture.addFileToProject("a.txt", "x").virtualFile, true)
        val wt = chats.open(ChatSpec.newWorktree(root, "wt2"))
        follower.follow(wt)
        assertEquals("wt2", follower.current?.name)
        assertEquals(setOf("a.txt"), openPaths())
        follower.refresh()
        assertEquals(setOf("a.txt"), openPaths()) // still no folder
        myFixture.addFileToProject(".claude/worktrees/wt2/a.txt", "x")
        follower.refresh()
        assertEquals(setOf(".claude/worktrees/wt2/a.txt"), openPaths())
        follower.refresh()
        assertEquals(setOf(".claude/worktrees/wt2/a.txt"), openPaths())
    }

    fun testMainLeavesFindAloneUntilAWorktreeIsFollowed() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(main)
        assertEquals("Mine", FindSettings.getInstance().defaultScopeName)
        assertEquals("IDE: main", main.checkoutText)
        assertEquals(listOf("Claude: main", "Claude: wt1"), scopeNames())
        follower.follow(wt)
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        follower.follow(main)
        assertEquals("Claude: main", FindSettings.getInstance().defaultScopeName)
    }

    fun testTurningFollowingOffRestoresTheUsersFindDefault() {
        FindSettings.getInstance().defaultScopeName = "Mine"
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        settings.followCheckout = false
        follower.refresh()
        assertEquals("Mine", FindSettings.getInstance().defaultScopeName)
        assertEquals("", wt.checkoutText)
        settings.followCheckout = true
        follower.refresh()
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
    }

    fun testShownCheckoutKeepsItsScopeAfterItsChatCloses() {
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val outside = chats.open(ChatSpec("x", "/elsewhere/x"))
        follower.follow(wt)
        chats.close(wt)
        follower.follow(outside)
        assertEquals("wt1", follower.current?.name)
        assertEquals(listOf("Claude: main", "Claude: wt1"), scopeNames())
        val main = chats.open(ChatSpec.inProject(root))
        follower.follow(main)
        follower.refresh()
        assertEquals(listOf("Claude: main"), scopeNames())
    }

    fun testSwapResultIsDroppedWhenTheTargetChangedBeforeItIsApplied() {
        openMainFileWithCounterpart()
        val queued = mutableListOf<Runnable>()
        follower.swapBackground = { queued += it }
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        follower.follow(wt)
        follower.follow(main)
        assertEquals(1, queued.size) // main -> wt1; wt1 -> main has no tab to look up
        queued.forEach { it.run() }
        assertEquals("main", follower.current?.name)
        assertEquals(setOf("a.txt"), openPaths()) // the main -> wt1 result would have swapped the tab
    }

    fun testSwapIsNotAppliedBeforeTheBackgroundLookupFinishes() {
        openMainFileWithCounterpart()
        val queued = mutableListOf<Runnable>()
        follower.swapBackground = { queued += it }
        follower.follow(chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1")))
        assertEquals(setOf("a.txt"), openPaths())
        queued.single().run()
        assertEquals(setOf(".claude/worktrees/wt1/a.txt"), openPaths())
    }
}
