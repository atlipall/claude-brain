# Claude Chat v3 — IDE Follows the Selected Chat's Checkout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user selects a chat tab whose folder is a `.claude/worktrees/<name>` checkout of the project, the IDE window follows it: the Project view shows that checkout's named scope, open editors swap to the same files in that checkout, Find in Files defaults to its scope, and the chat header says `IDE: <name>`.

**Architecture:** A new package `claudechat.follow`. Pure pieces: `CheckoutMapper` (folder → `Placement` of main / worktree / outside, file → counterpart path, all canonical), `ScopePatterns`, `EditorSwapPlan` (open tabs + carets → replacements), `ProjectViewChoice` (may we switch the Project view?), `Indicator` (header text), `AutoExclude` (registry value helpers). Platform glue: `CheckoutScopes` (local named scopes `Claude: <name>` in `NamedScopeManager`), `EditorSwapper`, `ProjectViewSwitcher`, `SearchDefaults`, and the project service `CheckoutFollower`, which the tool window's `ContentManagerListener.selectionChanged` and a new `Chat.onCwdChanged` hook drive. The header indicator is a new text-only bridge message `{"type":"checkout","text":...}`.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, WebStorm 2026.2.3 (build 262) via `local(...)`, JBR 25, JCEF, platform-bundled `kotlinx.serialization.json` (element API only), JUnit 4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-11-03-follow-chat-checkout-design.md` (extends `docs/superpowers/specs/2026-11-03-multi-chat-worktrees-design.md` and `docs/superpowers/specs/2026-11-03-claude-chat-webstorm-design.md`; everything there still applies unless v3 changes it).

## Global Constraints

- Repo `/Users/atli/projects/plugin`, branch `feature/claude-chat` (starts at `dacd0ea`). Commit on this branch after every task.
- No system Java: run Gradle only through `./gw` (sets `JAVA_HOME` to WebStorm's JBR 25). Examples: `./gw test --tests 'claudechat.follow.CheckoutsTest'`, `./gw test buildPlugin`.
- Target `/Applications/WebStorm.app` (2026.2.3, build 262); `sinceBuild = "262"`. Kotlin package root `claudechat`; plugin id `local.claudechat`; tool window id `Claude Chat`.
- JSON: only the `kotlinx.serialization.json` element API. No new dependencies.
- A chat's checkout is its `cwd` (predicted `.claude/worktrees/<name>` until `system/init`, then the reported one). The main checkout is the project base path; a chat whose cwd is the project dir (canonical comparison) is "main".
- Only checkouts under the project dir are followable (`<project>/.claude/worktrees/<name>`). A chat outside does not switch anything; its header says the checkout is outside this window and suggests opening it as a separate project.
- Named scopes: `Claude: main` = the project excluding `.claude/worktrees/**`; `Claude: <name>` = `.claude/worktrees/<name>/**` only. Local (workspace), not shared.
- Switch the Project view only if it currently shows one of the plugin's `Claude:` scopes or the default "Project" pane.
- Editor swap: same position, caret line/column kept (clamped), selection kept; files with no counterpart and files outside both checkouts stay open; save documents before swapping; never lose unsaved changes.
- Search defaults only through public API; where none exists, the scopes are only offered in the dropdowns.
- Header indicator: `IDE: main` / `IDE: <name>`, rendered with `textContent` only.
- Setting "IDE follows the selected chat's checkout", default on, in Settings > Tools > Claude Chat (`ClaudeChatSettings`, application level).
- Not changed by the plugin: indexing of `.claude/worktrees` (the plugin never adds an exclusion), Git widget, Commit tool window, run configurations, terminal.
- Security properties that must survive every task: DOMPurify `SANITIZE` config and the CSP in `index.html`; the `trusted` WeakSet gating `openFile`/permission/diff clicks; the bridge is injected only on the chat page and navigation away from it is blocked (`ChatPanel` `onLoadEnd` + `onBeforeBrowse`); `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` stripped in `ClaudeProcess`; `openUrl` accepts `http(s)` only.
- Tests never run the real `claude` binary (use `FakeConnection`). `BasePlatformTestCase` shares one light project across tests and project/application services persist: every test class resets the state it touches in `setUp`/`tearDown` (`Chats`, `NamedScopeManager` scopes, `FindSettings.defaultScopeName`, `ClaudeChatSettings.followCheckout`, open editors). Prefer `.txt` files. Light-test files live in an in-memory file system under the content root; compute it with `ProjectRootManager.getInstance(project).contentRoots.first().path`.
- Kotlin block comments nest: never write a scope pattern such as `file:*//*` inside `/* … */` or KDoc (it opens a nested comment and the file stops compiling). Use `//` line comments for them.

## Verified platform facts (build 262, checked with a throw-away probe test that has been deleted)

- `NamedScopeManager.getInstance(project)` (project service, `@State` storage `$WORKSPACE_FILE$` = local) extends `NamedScopesHolder`: public `getEditableScopes()`, `setScopes(NamedScope[])`, `getScope(String)`, `createScope(String, PackageSet)`, `addScopeListener`. `NamedScope.getScopeId()` / `getPresentableName()` both return the name passed to `createScope`.
- `PackageSetFactory.getInstance().compile(String)` throws `ParsingException`. Pattern semantics measured on `FilePatternPackageSet` in a light project: `file:*` matches only top-level files (`a.txt`), `file:*//*` only nested ones (`dir/b.txt`), `(file:*||file:*//*)&&!file:X//*` matches everything outside `X`, `file:wts/wt1//*` matches `wts/wt1/a.txt` and `wts/wt1/d/e/f.txt` but not `wts/wt10/a.txt`; names with `-` and `.` (`wts/wt-1.x`) work unquoted.
- **WebStorm 262 excludes `.claude/worktrees` by default.** `intellij.platform.projectModel.impl.jar` registers `AutoExcludeInContentRootsWorkspaceFileIndexContributor` driven by registry key `ide.workspace.model.relative.paths.to.exclude.automatically` (default `.worktrees;.claude/worktrees`, `restartRequired="true"`). In a light project `ProjectFileIndex.isExcluded(<root>/.claude/worktrees/wt1/a.txt)` is `true`, and no `file:` pattern matches excluded files (not even `file:*//*`). `Registry.get(key).asString()/setValue(String)` and `Application.restart()` are public.
- `ProjectView`: public `getCurrentProjectViewPane()`, `getProjectViewPaneById(String)`, `changeView(String, String)`. `ProjectViewPane.ID == "ProjectPane"`, `ScopeViewPane.ID == "Scope"`. Scope-pane sub-ids are **not** scope ids: they look like `Scope 'Claude: main'; set:file:*//*; class com.intellij.psi.search.scope.packageSet.NamedScope` (they embed the pattern), so the plugin finds a sub-id by `AbstractProjectViewPane.getPresentableSubIdName(subId) == scopeName`.
- `FileEditorManagerEx`: `getWindows()`, `getCurrentWindow()`, `setCurrentWindow()` public; `openFileWithProviders(file, focus, window)` is deprecated at ERROR level ("Use openFile()"); `openFile(file, window, FileEditorOpenOptions)` and the `FileEditorOpenOptions` class (the only API with a tab `index`) are `@ApiStatus.Internal`; `openFile(file, FileEditorOpenRequest(targetWindow = …, selectAsCurrent, requestFocus, …))` is `@ApiStatus.Experimental` (not internal) and returns a `FileEditorComposite` (`allEditors`). `EditorWindow.getFileList()`, `getSelectedFile()`, `setSelectedComposite(VirtualFile, Boolean)`, `closeFile(VirtualFile)` are public, not internal or deprecated. In light tests the editor manager is `TestEditorManagerImpl` (a `FileEditorManagerEx`) with **zero** windows.
- `FindSettings` (application service): public `getDefaultScopeName()/setDefaultScopeName(String)`. `FindManager.getInstance(project).getFindInProjectModel()` returns the live model (changes stick); `FindModel` has `setProjectScope`, `setModuleName`, `setDirectoryName`, `setCustomScope(Boolean)`, `setCustomScopeName`.
- Search Everywhere: no public API to set its default scope in 262; named scopes appear in its scope dropdown.

## File structure

```
src/main/kotlin/claudechat/follow/Checkouts.kt        CREATE Checkout, Placement, CheckoutMapper, ScopePatterns (pure)
src/main/kotlin/claudechat/follow/CheckoutScopes.kt   CREATE keeps the plugin's local named scopes in NamedScopeManager
src/main/kotlin/claudechat/follow/AutoExclude.kt      CREATE registry helpers + one-per-session "include worktrees" notification
src/main/kotlin/claudechat/follow/EditorSwapPlan.kt   CREATE Caret, OpenFile, Replacement, EditorSwapPlan (pure)
src/main/kotlin/claudechat/follow/EditorSwapper.kt    CREATE applies a swap plan to the open editors
src/main/kotlin/claudechat/follow/IdeViews.kt         CREATE ProjectViewChoice (pure), ProjectViewSwitcher, SearchDefaults
src/main/kotlin/claudechat/follow/CheckoutFollower.kt CREATE Indicator (pure) + project service that follows the selected chat
src/main/kotlin/claudechat/session/Chat.kt            MODIFY SessionView.checkout, Chat.onCwdChanged, Chat.showCheckout
src/main/kotlin/claudechat/ui/ChatBridge.kt           MODIFY posts {"type":"checkout"}
src/main/kotlin/claudechat/ui/ChatTabs.kt             MODIFY cwd changes refresh the follower
src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt MODIFY tab selection/removal drive the follower
src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt     MODIFY followCheckout (default true)
src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt MODIFY checkbox; apply() refreshes followers
src/main/resources/META-INF/plugin.xml                MODIFY notificationGroup "Claude Chat"
src/main/resources/webview/index.html, chat.js, chat.css      MODIFY header #checkout, text only
src/test/kotlin/claudechat/follow/*Test.kt            CREATE CheckoutsTest, CheckoutScopesTest, AutoExcludeTest, EditorSwapPlanTest,
                                                             EditorSwapperTest, ProjectViewChoiceTest, SearchDefaultsTest, CheckoutFollowerTest
src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt CREATE
src/test/kotlin/claudechat/session/ChatsTest.kt, ui/ChatBridgeTest.kt, ui/WebAssetsTest.kt MODIFY (new tests appended)
```

---

### Task 1: Checkout model, path mapping and scope patterns (pure)

**Files:**
- Create: `src/main/kotlin/claudechat/follow/Checkouts.kt`
- Test: `src/test/kotlin/claudechat/follow/CheckoutsTest.kt`

**Interfaces:**
- Consumes: `claudechat.git.canonicalPath(path: String): String` (v2).
- Produces (package `claudechat.follow`):
  - `data class Checkout(val name: String, val dir: String)` with `val isMain: Boolean`, `val scopeName: String` (`"Claude: " + name`), companion `MAIN = "main"`, `SCOPE_PREFIX = "Claude: "`.
  - `sealed interface Placement { data class Followable(val checkout: Checkout); data class Outside(val dir: String) }`.
  - `class CheckoutMapper(projectDir: String)` with `val main: Checkout`, `fun place(cwd: String): Placement`, `fun counterpart(path: String, from: Checkout, to: Checkout): String?`, companion `WORKTREES = ".claude/worktrees"`.
  - `object ScopePatterns { const val MAIN: String; fun under(relativeDir: String): String; fun forCheckout(checkout: Checkout): String }`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/follow/CheckoutsTest.kt`:
```kotlin
package claudechat.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CheckoutsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mapper = CheckoutMapper("/p")
    private val main = Checkout("main", "/p")
    private val wt1 = Checkout("wt1", "/p/.claude/worktrees/wt1")
    private val wt2 = Checkout("wt2", "/p/.claude/worktrees/wt2")

    @Test fun projectFolderIsMain() {
        assertEquals(Placement.Followable(main), mapper.place("/p/"))
        assertTrue(mapper.main.isMain)
        assertEquals("Claude: main", mapper.main.scopeName)
    }

    @Test fun worktreeFolderIsFollowable() {
        assertEquals(Placement.Followable(wt1), mapper.place("/p/.claude/worktrees/wt1"))
        assertEquals("Claude: wt1", wt1.scopeName)
    }

    @Test fun otherFoldersAreOutside() {
        assertEquals(Placement.Outside("/elsewhere/wt1"), mapper.place("/elsewhere/wt1"))
        assertEquals(Placement.Outside("/p/src"), mapper.place("/p/src"))
        assertEquals(Placement.Outside("/p/.claude/worktrees"), mapper.place("/p/.claude/worktrees"))
        assertEquals(Placement.Outside("/p/.claude/worktrees/wt1/sub"), mapper.place("/p/.claude/worktrees/wt1/sub"))
        assertEquals(Placement.Outside("/p/.claude/worktrees/has space"), mapper.place("/p/.claude/worktrees/has space"))
    }

    @Test fun placementComparesCanonicalPaths() {
        val project = tmp.newFolder("proj")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), project.toPath())
        File(project, ".claude/worktrees/wt1").mkdirs()
        val viaLink = CheckoutMapper(link.path)
        assertEquals(Placement.Followable(viaLink.main), viaLink.place(project.path))
        val placed = viaLink.place("${project.path}/.claude/worktrees/wt1") as Placement.Followable
        assertEquals("wt1", placed.checkout.name)
        assertEquals(File(project, ".claude/worktrees/wt1").canonicalPath, placed.checkout.dir)
    }

    @Test fun counterpartKeepsTheRelativePath() {
        assertEquals("/p/.claude/worktrees/wt1/src/a.ts", mapper.counterpart("/p/src/a.ts", main, wt1))
        assertEquals("/p/src/a.ts", mapper.counterpart("/p/.claude/worktrees/wt1/src/a.ts", wt1, main))
        assertEquals("/p/.claude/worktrees/wt2/a.ts", mapper.counterpart("/p/.claude/worktrees/wt1/a.ts", wt1, wt2))
    }

    @Test fun filesOutsideTheSourceCheckoutHaveNoCounterpart() {
        assertNull(mapper.counterpart("/other/a.ts", main, wt1))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt2/a.ts", wt1, main))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt1/a.ts", main, wt2))
        assertNull(mapper.counterpart("/p/.claude/worktrees/wt10/a.ts", wt1, main))
        assertNull(mapper.counterpart("/p", main, wt1))
    }

    @Test fun scopePatterns() {
        assertEquals("(file:*||file:*//*)&&!file:.claude/worktrees//*", ScopePatterns.forCheckout(main))
        assertEquals("file:.claude/worktrees/wt1//*", ScopePatterns.forCheckout(wt1))
        assertEquals("file:wts/a//*", ScopePatterns.under("/wts/a/"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.follow.CheckoutsTest'`
Expected: FAIL — `compileTestKotlin` reports `e: …/CheckoutsTest.kt… Unresolved reference 'CheckoutMapper'.` and `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/follow/Checkouts.kt`:
```kotlin
package claudechat.follow

import claudechat.git.canonicalPath

/**
 * A folder the IDE window can follow: the project itself ([MAIN]) or a `.claude/worktrees/<name>` folder in it.
 * [dir] is canonical.
 */
data class Checkout(val name: String, val dir: String) {
    val isMain: Boolean get() = name == MAIN

    /** The named scope the plugin keeps for this checkout. */
    val scopeName: String get() = SCOPE_PREFIX + name

    companion object {
        const val MAIN = "main"
        const val SCOPE_PREFIX = "Claude: "
    }
}

/** Where a chat's folder sits relative to this IDE window. */
sealed interface Placement {
    data class Followable(val checkout: Checkout) : Placement

    /** Anywhere else: another folder, or a worktree outside the project. [dir] is canonical. */
    data class Outside(val dir: String) : Placement
}

/** Maps folders and files to checkouts of the project at [projectDir]. Compares canonical paths. */
class CheckoutMapper(projectDir: String) {
    private val project = canonicalPath(projectDir)
    private val worktrees = "$project/$WORKTREES/"

    val main = Checkout(Checkout.MAIN, project)

    /** The project folder is main, `<project>/.claude/worktrees/<name>` is that worktree, anything else is outside. */
    fun place(cwd: String): Placement {
        val dir = canonicalPath(cwd)
        if (dir == project) return Placement.Followable(main)
        val name = dir.removePrefix(worktrees)
        return if (dir.startsWith(worktrees) && name.isNotEmpty() && '/' !in name && VALID_NAME.matches(name)) {
            Placement.Followable(Checkout(name, dir))
        } else {
            Placement.Outside(dir)
        }
    }

    /** [path] at the same relative path in [to], or null when [path] is not a file inside [from]. */
    fun counterpart(path: String, from: Checkout, to: Checkout): String? {
        val file = canonicalPath(path)
        val relative = file.removePrefix(from.dir.trimEnd('/') + "/")
        if (relative == file || relative.isEmpty()) return null
        // Main does not own the worktrees folder inside it.
        if (from.isMain && file.startsWith(worktrees)) return null
        return to.dir.trimEnd('/') + "/" + relative
    }

    companion object {
        const val WORKTREES = ".claude/worktrees"

        /** Folder names a scope pattern can hold without quoting (the names `claude --worktree` accepts). */
        private val VALID_NAME = Regex("[A-Za-z0-9._-]+")
    }
}

// Named-scope patterns, relative to the project content root. Checked against FilePatternPackageSet in build 262:
// "file:*" matches only top-level files and "file:*//*" only nested ones, so main needs both;
// "file:<dir>//*" matches every file at any depth under <dir> (and nothing under <dir>0).
// (Line comments: Kotlin block comments nest, so these patterns cannot appear in KDoc.)
object ScopePatterns {
    const val MAIN = "(file:*||file:*//*)&&!file:${CheckoutMapper.WORKTREES}//*"

    fun under(relativeDir: String): String = "file:${relativeDir.trim('/')}//*"

    fun forCheckout(checkout: Checkout): String =
        if (checkout.isMain) MAIN else under("${CheckoutMapper.WORKTREES}/${checkout.name}")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.follow.CheckoutsTest'`
Expected: PASS — `BUILD SUCCESSFUL`; `build/test-results/test/TEST-claudechat.follow.CheckoutsTest.xml` shows `tests="7" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/follow/Checkouts.kt src/test/kotlin/claudechat/follow/CheckoutsTest.kt
git commit -m "Map chat folders and files to project checkouts"
```

---

### Task 2: Named scopes per checkout, and the worktree auto-exclusion check

The plugin owns every local scope whose name starts with `Claude: `. Because WebStorm 262 excludes `.claude/worktrees` by default (see Verified platform facts), a worktree scope matches nothing until that exclusion is removed; this task also adds the once-per-session offer to remove it (the plugin never changes the registry without the user clicking).

**Files:**
- Create: `src/main/kotlin/claudechat/follow/CheckoutScopes.kt`
- Create: `src/main/kotlin/claudechat/follow/AutoExclude.kt`
- Modify: `src/main/resources/META-INF/plugin.xml:17` (add a notification group after the `toolWindow` element)
- Test: `src/test/kotlin/claudechat/follow/CheckoutScopesTest.kt`, `src/test/kotlin/claudechat/follow/AutoExcludeTest.kt`

**Interfaces:**
- Consumes: `Checkout`, `ScopePatterns.forCheckout`, `ScopePatterns.under`, `ScopePatterns.MAIN`, `CheckoutMapper.WORKTREES` (Task 1).
- Produces:
  - `class CheckoutScopes(project: Project)` with `fun sync(checkouts: List<Checkout>)` (EDT) and `fun find(checkout: Checkout): NamedScope?`.
  - `object AutoExclude { const val KEY: String; const val NOTIFICATION_GROUP = "Claude Chat"; fun excludesWorktrees(value: String): Boolean; fun withoutWorktrees(value: String): String; fun offerToInclude(project: Project) }`.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/follow/CheckoutScopesTest.kt`:
```kotlin
package claudechat.follow

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
```

`src/test/kotlin/claudechat/follow/AutoExcludeTest.kt`:
```kotlin
package claudechat.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExcludeTest {
    @Test fun platformDefaultExcludesWorktrees() = assertTrue(AutoExclude.excludesWorktrees(".worktrees;.claude/worktrees"))

    @Test fun trailingSlashAndSpacesStillCount() = assertTrue(AutoExclude.excludesWorktrees(" .claude/worktrees/ ;.worktrees"))

    @Test fun otherEntriesDoNotCount() {
        assertFalse(AutoExclude.excludesWorktrees(".worktrees"))
        assertFalse(AutoExclude.excludesWorktrees(""))
        assertFalse(AutoExclude.excludesWorktrees(".claude/worktrees-old"))
    }

    @Test fun withoutWorktreesKeepsTheRest() {
        assertEquals(".worktrees", AutoExclude.withoutWorktrees(".worktrees;.claude/worktrees"))
        assertEquals("", AutoExclude.withoutWorktrees(".claude/worktrees/"))
        assertEquals("a;b", AutoExclude.withoutWorktrees("a;.claude/worktrees;b"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.follow.CheckoutScopesTest' --tests 'claudechat.follow.AutoExcludeTest'`
Expected: FAIL — `e: … Unresolved reference 'CheckoutScopes'.` / `Unresolved reference 'AutoExclude'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/follow/CheckoutScopes.kt`:
```kotlin
package claudechat.follow

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

    /** Makes the plugin's scopes exactly [checkouts] (first one wins for a repeated name). */
    fun sync(checkouts: List<Checkout>) {
        val ours = checkouts.distinctBy { it.scopeName }.mapNotNull { c ->
            compile(ScopePatterns.forCheckout(c))?.let { holder.createScope(c.scopeName, it) }
        }
        val current = holder.editableScopes.toList()
        val next = current.filterNot { it.scopeId.startsWith(Checkout.SCOPE_PREFIX) } + ours
        if (next.map(::key) == current.map(::key)) return // unchanged: do not make every scope view rebuild
        holder.scopes = next.toTypedArray()
    }

    fun find(checkout: Checkout): NamedScope? = holder.getScope(checkout.scopeName)

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
```

`src/main/kotlin/claudechat/follow/AutoExclude.kt`:
```kotlin
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
```

In `src/main/resources/META-INF/plugin.xml` replace line 17:
```xml
                    canCloseContents="true" factoryClass="claudechat.ui.ChatToolWindowFactory"/>
```
with:
```xml
                    canCloseContents="true" factoryClass="claudechat.ui.ChatToolWindowFactory"/>
        <notificationGroup id="Claude Chat" displayType="BALLOON"/>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.follow.CheckoutScopesTest' --tests 'claudechat.follow.AutoExcludeTest'`
Expected: PASS — `BUILD SUCCESSFUL`; CheckoutScopesTest 6 tests, AutoExcludeTest 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/follow/CheckoutScopes.kt src/main/kotlin/claudechat/follow/AutoExclude.kt \
  src/main/resources/META-INF/plugin.xml \
  src/test/kotlin/claudechat/follow/CheckoutScopesTest.kt src/test/kotlin/claudechat/follow/AutoExcludeTest.kt
git commit -m "Keep a local named scope per followed checkout"
```

---

### Task 3: Editor swap planner and applier

**Files:**
- Create: `src/main/kotlin/claudechat/follow/EditorSwapPlan.kt`
- Create: `src/main/kotlin/claudechat/follow/EditorSwapper.kt`
- Test: `src/test/kotlin/claudechat/follow/EditorSwapPlanTest.kt`, `src/test/kotlin/claudechat/follow/EditorSwapperTest.kt`

**Interfaces:**
- Consumes: `Checkout`, `CheckoutMapper(projectDir)`, `CheckoutMapper.main`, `CheckoutMapper.counterpart` (Task 1).
- Produces:
  - `data class Caret(val line: Int, val column: Int)` with `fun clamp(lineCount: Int, lineLength: (Int) -> Int): Caret`.
  - `data class OpenFile(val path: String, val caret: Caret?)`, `data class Replacement(val from: String, val to: String, val caret: Caret?)`.
  - `object EditorSwapPlan { fun plan(open: List<OpenFile>, from: Checkout, to: Checkout, mapper: CheckoutMapper, exists: (String) -> Boolean): List<Replacement> }`.
  - `class EditorSwapper(project: Project, mapper: CheckoutMapper)` with `fun swap(from: Checkout, to: Checkout)` (EDT).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/follow/EditorSwapPlanTest.kt`:
```kotlin
package claudechat.follow

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSwapPlanTest {
    private val mapper = CheckoutMapper("/p")
    private val main = Checkout("main", "/p")
    private val wt1 = Checkout("wt1", "/p/.claude/worktrees/wt1")
    private val wt2 = Checkout("wt2", "/p/.claude/worktrees/wt2")
    private val all = { _: String -> true }

    @Test fun swapsFilesOfTheOldCheckoutInTabOrder() {
        val open = listOf(OpenFile("/p/b.ts", Caret(3, 4)), OpenFile("/p/src/a.ts", null))
        assertEquals(
            listOf(
                Replacement("/p/b.ts", "/p/.claude/worktrees/wt1/b.ts", Caret(3, 4)),
                Replacement("/p/src/a.ts", "/p/.claude/worktrees/wt1/src/a.ts", null),
            ),
            EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun filesWithoutCounterpartStay() {
        val open = listOf(OpenFile("/p/new.ts", null), OpenFile("/p/old.ts", null))
        val exists = { p: String -> p == "/p/.claude/worktrees/wt1/old.ts" }
        assertEquals(listOf(Replacement("/p/old.ts", "/p/.claude/worktrees/wt1/old.ts", null)),
            EditorSwapPlan.plan(open, main, wt1, mapper, exists))
    }

    @Test fun filesOutsideTheOldCheckoutStay() {
        val open = listOf(OpenFile("/elsewhere/x.ts", null), OpenFile("/p/.claude/worktrees/wt2/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, main, wt1, mapper, all))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, wt1, main, mapper, all))
    }

    @Test fun worktreeToWorktree() {
        val open = listOf(OpenFile("/p/.claude/worktrees/wt1/a.ts", Caret(0, 0)), OpenFile("/p/a.ts", null))
        assertEquals(listOf(Replacement("/p/.claude/worktrees/wt1/a.ts", "/p/.claude/worktrees/wt2/a.ts", Caret(0, 0))),
            EditorSwapPlan.plan(open, wt1, wt2, mapper, all))
    }

    @Test fun counterpartAlreadyOpenIsLeftAlone() {
        val open = listOf(OpenFile("/p/a.ts", null), OpenFile("/p/.claude/worktrees/wt1/a.ts", null))
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun sameCheckoutPlansNothing() =
        assertEquals(emptyList<Replacement>(), EditorSwapPlan.plan(listOf(OpenFile("/p/a.ts", null)), main, main, mapper, all))

    @Test fun duplicateTabsAreSwappedOnce() {
        val open = listOf(OpenFile("/p/a.ts", Caret(1, 1)), OpenFile("/p/a.ts", Caret(9, 9)))
        assertEquals(listOf(Replacement("/p/a.ts", "/p/.claude/worktrees/wt1/a.ts", Caret(1, 1))),
            EditorSwapPlan.plan(open, main, wt1, mapper, all))
    }

    @Test fun caretIsClampedToTheNewDocument() {
        val lengths = listOf(5, 2)
        assertEquals(Caret(1, 2), Caret(7, 9).clamp(2) { lengths[it] })
        assertEquals(Caret(0, 3), Caret(0, 3).clamp(2) { lengths[it] })
        assertEquals(Caret(0, 0), Caret(4, 4).clamp(0) { 0 })
        assertEquals(Caret(0, 0), Caret(-1, -1).clamp(2) { lengths[it] })
    }
}
```

`src/test/kotlin/claudechat/follow/EditorSwapperTest.kt` (light tests have no editor splits, so this covers the split-less path; the split path is checked in Task 6):
```kotlin
package claudechat.follow

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorSwapperTest : BasePlatformTestCase() {
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val mapper get() = CheckoutMapper(root)
    private val main get() = mapper.main
    private val wt1 get() = Checkout("wt1", "$root/.claude/worktrees/wt1")
    private val manager get() = FileEditorManager.getInstance(project)

    override fun tearDown() {
        try { manager.openFiles.forEach(manager::closeFile) } finally { super.tearDown() }
    }

    private fun file(path: String, text: String = "one\ntwo\nthree\n"): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun openPaths() = manager.openFiles.map { it.path.removePrefix("$root/") }.toSet()

    private fun caret(file: VirtualFile) =
        (manager.getSelectedEditor(file) as TextEditor).editor.caretModel.logicalPosition

    fun testSwapsFilesThatHaveACounterpartAndKeepsTheRest() {
        val a = file("a.txt")
        val onlyMain = file("only-main.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(onlyMain, false)
        manager.openFile(a, true)
        EditorSwapper(project, mapper).swap(main, wt1)
        assertEquals(setOf(".claude/worktrees/wt1/a.txt", "only-main.txt"), openPaths())
        assertEquals(".claude/worktrees/wt1/a.txt", manager.selectedFiles.first().path.removePrefix("$root/"))
    }

    fun testCaretIsKeptAndClamped() {
        val a = file("a.txt", "0123456789\n0123456789\n0123456789\n")
        val b = file("b.txt", "0123456789\n0123456789\n")
        file(".claude/worktrees/wt1/a.txt", "0123456789\n0123456789\n0123456789\n")
        file(".claude/worktrees/wt1/b.txt", "x\n")
        manager.openFile(a, false)
        manager.openFile(b, false)
        (manager.getSelectedEditor(a) as TextEditor).editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 4))
        (manager.getSelectedEditor(b) as TextEditor).editor.caretModel.moveToLogicalPosition(LogicalPosition(1, 9))
        EditorSwapper(project, mapper).swap(main, wt1)
        assertEquals(LogicalPosition(2, 4), caret(myFixture.findFileInTempDir(".claude/worktrees/wt1/a.txt")))
        assertEquals(LogicalPosition(1, 0), caret(myFixture.findFileInTempDir(".claude/worktrees/wt1/b.txt")))
    }

    fun testUnsavedChangesAreSavedBeforeTheSwap() {
        val a = file("a.txt", "old")
        file(".claude/worktrees/wt1/a.txt")
        myFixture.openFileInEditor(a)
        myFixture.type("new ")
        EditorSwapper(project, mapper).swap(main, wt1)
        assertFalse(FileDocumentManager.getInstance().isFileModified(a))
        assertEquals("new old", String(a.contentsToByteArray()))
    }

    fun testSwapsBack() {
        val a = file("a.txt")
        file(".claude/worktrees/wt1/a.txt")
        manager.openFile(a, true)
        val swapper = EditorSwapper(project, mapper)
        swapper.swap(main, wt1)
        swapper.swap(wt1, main)
        assertEquals(setOf("a.txt"), openPaths())
    }

    fun testNothingOpenIsANoOp() {
        EditorSwapper(project, mapper).swap(main, wt1)
        assertTrue(manager.openFiles.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.follow.EditorSwapPlanTest' --tests 'claudechat.follow.EditorSwapperTest'`
Expected: FAIL — `e: … Unresolved reference 'EditorSwapPlan'.` / `Unresolved reference 'EditorSwapper'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/follow/EditorSwapPlan.kt`:
```kotlin
package claudechat.follow

/** A zero-based caret position. */
data class Caret(val line: Int, val column: Int) {
    /** This position moved inside a document of [lineCount] lines (at least one) whose line lengths [lineLength] gives. */
    fun clamp(lineCount: Int, lineLength: (Int) -> Int): Caret {
        val l = line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        return Caret(l, column.coerceIn(0, lineLength(l).coerceAtLeast(0)))
    }
}

/** An open editor tab: its file path and, for a text editor, its caret. */
data class OpenFile(val path: String, val caret: Caret?)

/** Replace the tab showing [from] with [to] in the same place, putting the caret at [caret] (clamped) when known. */
data class Replacement(val from: String, val to: String, val caret: Caret?)

object EditorSwapPlan {
    /**
     * Which open tabs to swap when the IDE moves from checkout [from] to [to]: every file in [from] whose counterpart
     * [exists] and is not already open. Files outside [from], without a counterpart, or whose counterpart already has a
     * tab stay as they are. Each file appears once, in [open] order.
     */
    fun plan(open: List<OpenFile>, from: Checkout, to: Checkout, mapper: CheckoutMapper, exists: (String) -> Boolean): List<Replacement> {
        if (from == to) return emptyList()
        val openPaths = open.map { it.path }.toSet()
        return open.distinctBy { it.path }.mapNotNull { file ->
            val target = mapper.counterpart(file.path, from, to) ?: return@mapNotNull null
            if (target in openPaths || !exists(target)) null else Replacement(file.path, target, file.caret)
        }
    }
}
```

`src/main/kotlin/claudechat/follow/EditorSwapper.kt`:
```kotlin
package claudechat.follow

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Swaps open editor tabs from one checkout to another (see [EditorSwapPlan]). EDT only. */
class EditorSwapper(private val project: Project, private val mapper: CheckoutMapper) {
    private val manager get() = FileEditorManager.getInstance(project)

    private data class Swap(val old: VirtualFile, val new: VirtualFile, val caret: Caret?)

    fun swap(from: Checkout, to: Checkout) {
        val files = manager.openFiles.toList()
        if (files.isEmpty()) return
        val byPath = files.associateBy { it.path }
        val fs = files.first().fileSystem // the local file system; an in-memory one in light tests
        val open = files.map { OpenFile(it.path, caretOf(it)) }
        val swaps = EditorSwapPlan.plan(open, from, to, mapper) { fs.findFileByPath(it) != null }.mapNotNull { r ->
            val old = byPath[r.from] ?: return@mapNotNull null
            val new = fs.findFileByPath(r.to) ?: return@mapNotNull null
            Swap(old, new, r.caret)
        }
        if (swaps.isEmpty()) return
        // A closed editor's unsaved changes stay in memory; saving first means none wait behind a tab that is gone.
        val documents = FileDocumentManager.getInstance()
        swaps.forEach { s -> documents.getCachedDocument(s.old)?.let(documents::saveDocument) }
        val ex = manager as? FileEditorManagerEx
        val windows = ex?.windows.orEmpty()
        if (ex == null || windows.isEmpty()) swapWithoutWindows(swaps) else swapInWindows(ex, windows.toList(), swaps)
    }

    /**
     * In each editor split: select the old tab, open the counterpart in that split (a new tab opens right after the
     * selected one unless "Open new tabs at the end" is on), close the old tab, then restore the split's selection.
     */
    private fun swapInWindows(ex: FileEditorManagerEx, windows: List<EditorWindow>, swaps: List<Swap>) {
        val current = ex.currentWindow
        for (window in windows) {
            val selected = window.selectedFile
            var reselect = selected
            for (s in swaps) {
                if (s.old !in window.fileList) continue
                window.setSelectedComposite(s.old, false)
                val request = FileEditorOpenRequest(targetWindow = window, selectAsCurrent = false, requestFocus = false)
                val editors = ex.openFile(s.new, request).allEditors
                window.closeFile(s.old)
                placeCaret(s.new, editors, s.caret)
                if (s.old == selected) reselect = s.new
            }
            reselect?.let { window.setSelectedComposite(it, false) }
        }
        current?.let { ex.currentWindow = it }
    }

    /** No editor splits (light tests): open the counterpart, close the old file, keep the selected file selected. */
    private fun swapWithoutWindows(swaps: List<Swap>) {
        val selected = manager.selectedFiles.firstOrNull()
        var reselect: VirtualFile? = null
        for (s in swaps) {
            val editors = manager.openFile(s.new, false)
            manager.closeFile(s.old)
            placeCaret(s.new, editors.toList(), s.caret)
            if (s.old == selected) reselect = s.new
        }
        reselect?.let { manager.openFile(it, true) }
    }

    private fun caretOf(file: VirtualFile): Caret? =
        (manager.getSelectedEditor(file) as? TextEditor)?.editor?.caretModel?.logicalPosition?.let { Caret(it.line, it.column) }

    private fun placeCaret(file: VirtualFile, editors: List<FileEditor>, caret: Caret?) {
        caret ?: return
        val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
            ?: (manager.getSelectedEditor(file) as? TextEditor)?.editor ?: return
        val document = editor.document
        val at = caret.clamp(document.lineCount) { document.getLineEndOffset(it) - document.getLineStartOffset(it) }
        editor.caretModel.moveToLogicalPosition(LogicalPosition(at.line, at.column))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }
}
```

Do not replace `ex.openFile(s.new, request)` with `openFileWithProviders(file, focus, window)` (deprecated at ERROR level in 262, does not compile) or with `openFile(file, window, FileEditorOpenOptions(index = …))` (`@ApiStatus.Internal`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.follow.EditorSwapPlanTest' --tests 'claudechat.follow.EditorSwapperTest'`
Expected: PASS — `BUILD SUCCESSFUL`; EditorSwapPlanTest 8 tests, EditorSwapperTest 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/follow/EditorSwapPlan.kt src/main/kotlin/claudechat/follow/EditorSwapper.kt \
  src/test/kotlin/claudechat/follow/EditorSwapPlanTest.kt src/test/kotlin/claudechat/follow/EditorSwapperTest.kt
git commit -m "Swap open editors to the same files in another checkout"
```

---

### Task 4: Project view switching, Find in Files default, and the setting

Search Everywhere has no public API for a default scope in build 262 (spec fallback): its scope dropdown lists the `Claude:` scopes, nothing more is done for it.

**Files:**
- Create: `src/main/kotlin/claudechat/follow/IdeViews.kt`
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt:18`
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt:7-8,30-31`
- Test: `src/test/kotlin/claudechat/follow/ProjectViewChoiceTest.kt`, `src/test/kotlin/claudechat/follow/SearchDefaultsTest.kt`, `src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt`

**Interfaces:**
- Consumes: `Checkout.SCOPE_PREFIX` (Task 1).
- Produces:
  - `object ProjectViewChoice { fun shouldSwitch(paneId: String?, scopeName: String?): Boolean }`.
  - `class ProjectViewSwitcher(project: Project)` with `fun show(scopeName: String)` (EDT).
  - `object SearchDefaults { fun use(project: Project, scopeName: String) }`.
  - `ClaudeChatSettings.State.followCheckout: Boolean` (default `true`).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/follow/ProjectViewChoiceTest.kt`:
```kotlin
package claudechat.follow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectViewChoiceTest {
    @Test fun projectPaneFollows() = assertTrue(ProjectViewChoice.shouldSwitch("ProjectPane", null))

    @Test fun pluginScopeFollows() = assertTrue(ProjectViewChoice.shouldSwitch("Scope", "Claude: wt1"))

    @Test fun userScopeIsLeftAlone() {
        assertFalse(ProjectViewChoice.shouldSwitch("Scope", "Project Files"))
        assertFalse(ProjectViewChoice.shouldSwitch("Scope", null))
    }

    @Test fun otherPanesAreLeftAlone() {
        assertFalse(ProjectViewChoice.shouldSwitch("PackagesPane", null))
        assertFalse(ProjectViewChoice.shouldSwitch(null, null))
    }
}
```

`src/test/kotlin/claudechat/follow/SearchDefaultsTest.kt`:
```kotlin
package claudechat.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SearchDefaultsTest : BasePlatformTestCase() {
    private var savedScopeName: String? = null

    override fun setUp() {
        super.setUp()
        savedScopeName = FindSettings.getInstance().defaultScopeName // application-wide
    }

    override fun tearDown() {
        try { FindSettings.getInstance().defaultScopeName = savedScopeName } finally { super.tearDown() }
    }

    fun testFindInFilesDefaultsToTheScope() {
        FindManager.getInstance(project).findInProjectModel.directoryName = "/somewhere"
        SearchDefaults.use(project, "Claude: wt1")
        assertEquals("Claude: wt1", FindSettings.getInstance().defaultScopeName)
        val model = FindManager.getInstance(project).findInProjectModel
        assertTrue(model.isCustomScope)
        assertFalse(model.isProjectScope)
        assertNull(model.directoryName)
        assertNull(model.moduleName)
        assertEquals("Claude: wt1", model.customScopeName)
    }
}
```

`src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt`:
```kotlin
package claudechat.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeChatSettingsTest {
    @Test fun followingIsOnByDefault() = assertTrue(ClaudeChatSettings.State().followCheckout)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.follow.ProjectViewChoiceTest' --tests 'claudechat.follow.SearchDefaultsTest' --tests 'claudechat.settings.ClaudeChatSettingsTest'`
Expected: FAIL — `e: … Unresolved reference 'ProjectViewChoice'.`, `Unresolved reference 'SearchDefaults'.`, `Unresolved reference 'followCheckout'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/follow/IdeViews.kt`:
```kotlin
package claudechat.follow

import com.intellij.find.FindManager
import com.intellij.find.FindSettings
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.scopeView.ScopeViewPane
import com.intellij.openapi.project.Project

object ProjectViewChoice {
    /**
     * Whether following may change the Project tool window: only while it shows the plain Project pane
     * ([ProjectViewPane.ID], "ProjectPane") or one of the plugin's scopes in the Scope pane ([ScopeViewPane.ID], "Scope").
     * [scopeName] is the presentable name of the scope shown, null for other panes.
     */
    fun shouldSwitch(paneId: String?, scopeName: String?): Boolean = when (paneId) {
        ProjectViewPane.ID -> true
        ScopeViewPane.ID -> scopeName?.startsWith(Checkout.SCOPE_PREFIX) == true
        else -> false
    }
}

/** Switches the Project tool window to a plugin scope. EDT only. */
class ProjectViewSwitcher(private val project: Project) {
    /** Shows the named scope [scopeName] if [ProjectViewChoice] allows it; does nothing if the scope pane does not list it. */
    fun show(scopeName: String) {
        val view = ProjectView.getInstance(project)
        val pane = view.currentProjectViewPane
        // Sub-ids of the Scope pane embed the scope's pattern text, so they are looked up by presentable name.
        val shown = pane?.takeIf { it.id == ScopeViewPane.ID }?.subId?.let(pane::getPresentableSubIdName)
        if (shown == scopeName || !ProjectViewChoice.shouldSwitch(pane?.id, shown)) return
        val scopes = view.getProjectViewPaneById(ScopeViewPane.ID) ?: return
        val subId = scopes.subIds.firstOrNull { scopes.getPresentableSubIdName(it) == scopeName } ?: return
        view.changeView(ScopeViewPane.ID, subId)
    }
}

/**
 * Find in Files opens on the given named scope. Search Everywhere has no public API for its default scope in build 262,
 * so there the plugin's scopes are only offered in the scope dropdown.
 */
object SearchDefaults {
    fun use(project: Project, scopeName: String) {
        FindSettings.getInstance().defaultScopeName = scopeName // preselected in the popup's Scope dropdown (application-wide)
        FindManager.getInstance(project).findInProjectModel.apply {
            isProjectScope = false
            moduleName = null
            directoryName = null
            isCustomScope = true
            customScopeName = scopeName
        }
    }
}
```

In `src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt` replace line 18:
```kotlin
        var extraArgs by string("")
```
with:
```kotlin
        var extraArgs by string("")
        /** The IDE window follows the selected chat's checkout (Project view scope, open editors, Find scope). */
        var followCheckout by property(true)
```

In `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt` replace lines 7-8:
```kotlin
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
```
with:
```kotlin
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
```
and replace lines 30-31:
```kotlin
                .comment("For example: --model sonnet")
        }
```
with:
```kotlin
                .comment("For example: --model sonnet")
        }
        row {
            checkBox("IDE follows the selected chat's checkout")
                .bindSelected({ state.followCheckout }, { state.followCheckout = it })
                .comment("Selecting a worktree chat scopes the Project view and Find in Files to that worktree and swaps open editors to its files.")
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.follow.ProjectViewChoiceTest' --tests 'claudechat.follow.SearchDefaultsTest' --tests 'claudechat.settings.ClaudeChatSettingsTest'`
Expected: PASS — `BUILD SUCCESSFUL`; 4 + 1 + 1 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/follow/IdeViews.kt \
  src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt \
  src/test/kotlin/claudechat/follow/ProjectViewChoiceTest.kt src/test/kotlin/claudechat/follow/SearchDefaultsTest.kt \
  src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt
git commit -m "Switch the Project view and Find scope to a checkout, behind a setting"
```

---

### Task 5: Follow on tab selection and cwd change, with the header indicator

**Files:**
- Create: `src/main/kotlin/claudechat/follow/CheckoutFollower.kt`
- Modify: `src/main/kotlin/claudechat/session/Chat.kt:12-13,37,45-47,62,67,80`
- Modify: `src/main/kotlin/claudechat/ui/ChatBridge.kt:98`
- Modify: `src/main/kotlin/claudechat/ui/ChatTabs.kt:3-6,29`
- Modify: `src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt:3-11,21`
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt:3-5,14`
- Modify: `src/main/resources/webview/index.html:17`, `src/main/resources/webview/chat.js:10,53,171`, `src/main/resources/webview/chat.css:10`
- Test: `src/test/kotlin/claudechat/follow/CheckoutFollowerTest.kt` (create); `src/test/kotlin/claudechat/session/ChatsTest.kt:134`, `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt:127`, `src/test/kotlin/claudechat/ui/WebAssetsTest.kt:30` (modify)

**Interfaces:**
- Consumes: `CheckoutMapper`, `Placement`, `Checkout` (Task 1); `CheckoutScopes.sync`, `AutoExclude.offerToInclude` (Task 2); `EditorSwapper.swap` (Task 3); `ProjectViewSwitcher.show`, `SearchDefaults.use`, `ClaudeChatSettings.State.followCheckout` (Task 4); `Chats.all()`, `Chats.projectDir`, `Chat.cwd`, `ChatTabs.CHAT` (v2).
- Produces:
  - `SessionView.checkout(text: String)` (default no-op), `Chat.onCwdChanged: (() -> Unit)?` (called on the CLI reader thread), `Chat.checkoutText: String`, `Chat.showCheckout(text: String)`.
  - Bridge message Kotlin → JS `{"type":"checkout","text":"IDE: wt1"}`; empty `text` hides the indicator.
  - `object Indicator { fun text(enabled: Boolean, ide: Checkout, chat: Placement): String }`.
  - `@Service(PROJECT) class CheckoutFollower(project)` with `var projectDir: String`, `val current: Checkout?`, `fun follow(chat: Chat?)`, `fun refresh()` (EDT), `companion fun getInstance(project)`.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/follow/CheckoutFollowerTest.kt`:
```kotlin
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
    private lateinit var follower: CheckoutFollower

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        chats.connector = { FakeConnection().also { connections += it } }
        holder.scopes = emptyArray()
        savedScopeName = FindSettings.getInstance().defaultScopeName
        settings.followCheckout = true
        follower = CheckoutFollower(project).also { it.projectDir = root } // a fresh follower: IDE shows main
    }

    override fun tearDown() {
        try {
            manager.openFiles.forEach(manager::closeFile)
            chats.closeAll()
            holder.scopes = emptyArray()
            FindSettings.getInstance().defaultScopeName = savedScopeName
            settings.followCheckout = true
        } finally {
            super.tearDown()
        }
    }

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
        assertEquals(listOf("Claude: main"), scopeNames())
        assertTrue(wt.checkoutText, wt.checkoutText.startsWith("IDE: wt2 · /elsewhere/wt2 is outside this window"))
    }

    fun testIndicatorText() {
        val main = Checkout("main", "/p")
        assertEquals("", Indicator.text(false, main, Placement.Followable(main)))
        assertEquals("IDE: main", Indicator.text(true, main, Placement.Followable(main)))
        assertEquals("IDE: main · /x is outside this window; open it as a separate project to follow it",
            Indicator.text(true, main, Placement.Outside("/x")))
    }
}
```

In `src/test/kotlin/claudechat/session/ChatsTest.kt` insert before line 134 (`fun testDeadChatIgnoresSend() {`):
```kotlin
    fun testCwdChangeIsReportedOnlyWhenTheFolderChanges() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        var changes = 0
        chat.onCwdChanged = { changes++ }
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals(0, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
        connections.single().emit(Fx.init(cwd = "/elsewhere/real"))
        assertEquals(1, changes)
    }

    fun testCheckoutTextIsReplayedOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.showCheckout("IDE: wt1")
        val view = object : SessionView by RecordingView() {
            val checkouts = mutableListOf<String>()
            override fun checkout(text: String) { checkouts += text }
        }
        chat.attach(view)
        assertEquals(listOf("IDE: wt1"), view.checkouts)
        chat.showCheckout("IDE: main")
        chat.showCheckout("IDE: main")
        assertEquals(listOf("IDE: wt1", "IDE: main"), view.checkouts)
    }

```

In `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt` insert before line 127 (`fun testDisposedBridgeStopsReceivingHistory() {`):
```kotlin
    fun testCheckoutIsPostedAsText() {
        bridge.handle("""{"type":"ready"}""")
        chat.showCheckout("IDE: wt1")
        val msg = posted.last()
        assertEquals("checkout", msg.str("type"))
        assertEquals("IDE: wt1", msg.str("text"))
    }

    fun testReadyReplaysTheCheckout() {
        chat.showCheckout("IDE: main")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "state", "checkout"), types())
    }

```

In `src/test/kotlin/claudechat/ui/WebAssetsTest.kt` insert before line 30 (`@Test fun pageHandlesHistoryRecall() {`):
```kotlin
    @Test fun pageShowsCheckoutAsPlainText() {
        val page = WebAssets.page()
        assertTrue(page.contains("<span id=\"checkout\" hidden></span>"))
        assertTrue(page.contains("case 'checkout': setCheckout(msg.text);"))
        assertTrue(page.contains("checkoutEl.textContent ="))
        assertFalse(page.contains("checkoutEl.innerHTML"))
    }

```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.follow.CheckoutFollowerTest' --tests 'claudechat.session.ChatsTest' --tests 'claudechat.ui.ChatBridgeTest' --tests 'claudechat.ui.WebAssetsTest'`
Expected: FAIL — `e: … Unresolved reference 'CheckoutFollower'.`, `Unresolved reference 'onCwdChanged'.`, `Unresolved reference 'showCheckout'.`, `BUILD FAILED`.

- [ ] **Step 3: Add the cwd hook and the checkout text to `Chat`**

In `src/main/kotlin/claudechat/session/Chat.kt`:

Replace lines 12-13:
```kotlin
    fun insert(text: String)
}
```
with:
```kotlin
    fun insert(text: String)

    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}
}
```

Replace line 37:
```kotlin
    @Volatile var onTitleChanged: (() -> Unit)? = null
```
with:
```kotlin
    @Volatile var onTitleChanged: (() -> Unit)? = null

    /** Called on the CLI reader thread, with the session lock held, when [cwd] changes (system/init reported another folder). */
    @Volatile var onCwdChanged: (() -> Unit)? = null
    @Volatile private var knownCwd: String? = null

    /** The header text the page shows (see [SessionView.checkout]). */
    @Volatile var checkoutText: String = ""
        private set
```

Replace lines 45-47:
```kotlin
        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
        }
```
with:
```kotlin
        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
            val now = cwd
            if (now != knownCwd) {
                knownCwd = now
                onCwdChanged?.invoke()
            }
        }
```

Replace line 62:
```kotlin
    fun start() = session.start()
```
with:
```kotlin
    fun start() {
        knownCwd = cwd
        session.start()
    }
```

Replace line 67:
```kotlin
        view.state(session.state, session.sessionId, session.mode)
```
with:
```kotlin
        view.state(session.state, session.sessionId, session.mode)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
```

Insert before line 80 (`/** EDT only (the edit note saves documents). Ignored while the session is dead. */`):
```kotlin
    fun showCheckout(text: String) {
        if (text == checkoutText) return
        checkoutText = text
        view?.checkout(text)
    }

```

- [ ] **Step 4: Post the indicator from `ChatBridge` and render it as text**

In `src/main/kotlin/claudechat/ui/ChatBridge.kt` insert before line 98 (`private fun historyMessage(entries: List<String>) = buildJsonObject {`):
```kotlin
    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())

```

In `src/main/resources/webview/index.html` replace line 17:
```html
  <span id="status"></span>
```
with:
```html
  <span id="checkout" hidden></span>
  <span id="status"></span>
```

In `src/main/resources/webview/chat.css` replace line 10:
```css
#status { color: var(--muted); margin-left: auto; }
```
with:
```css
#status { color: var(--muted); margin-left: auto; }
#checkout { color: var(--muted); font-size: .9em; overflow-wrap: anywhere; }
```

In `src/main/resources/webview/chat.js`:

Replace line 10:
```js
  const statusEl = document.getElementById('status');
```
with:
```js
  const statusEl = document.getElementById('status');
  const checkoutEl = document.getElementById('checkout');
```

Replace line 53:
```js
        case 'history': history = Array.isArray(msg.entries) ? msg.entries : []; break;
```
with:
```js
        case 'history': history = Array.isArray(msg.entries) ? msg.entries : []; break;
        case 'checkout': setCheckout(msg.text); break;
```

Insert before line 171 (`function insertText(text) {`):
```js
  // Plain text only: the IDE's checkout name and folder never become markup.
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }

```

- [ ] **Step 5: Create the follower**

`src/main/kotlin/claudechat/follow/CheckoutFollower.kt`:
```kotlin
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
    fun text(enabled: Boolean, ide: Checkout, chat: Placement): String = when {
        !enabled -> ""
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
        if (enabled) {
            CheckoutScopes(project).sync(listOf(mapper.main) + chats.all().mapNotNull { followable(mapper, it) }.filterNot { it.isMain })
            val target = selected?.let { followable(mapper, it) }
            if (target != null && (selecting || target != (current ?: mapper.main))) show(mapper, target)
        }
        val ide = current ?: mapper.main
        chats.all().forEach { it.showCheckout(Indicator.text(enabled, ide, mapper.place(it.cwd))) }
    }

    private fun show(mapper: CheckoutMapper, target: Checkout) {
        val shown = current ?: mapper.main
        if (target != shown) EditorSwapper(project, mapper).swap(shown, target)
        current = target
        SearchDefaults.use(project, target.scopeName)
        // The Scope pane learns about new scopes from a listener; switch after it has.
        ApplicationManager.getApplication().invokeLater({ ProjectViewSwitcher(project).show(target.scopeName) }, project.disposed)
        if (!target.isMain) AutoExclude.offerToInclude(project)
    }

    private fun followable(mapper: CheckoutMapper, chat: Chat): Checkout? = (mapper.place(chat.cwd) as? Placement.Followable)?.checkout

    companion object {
        fun getInstance(project: Project): CheckoutFollower = project.service()
    }
}
```

- [ ] **Step 6: Wire tab selection, tab removal, cwd changes and the setting**

In `src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt` replace lines 3-11 (the imports) with:
```kotlin
import claudechat.follow.CheckoutFollower
import claudechat.session.Chats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.jcef.JBCefApp
```
and replace line 21:
```kotlin
        val tabs = ChatTabs(project, toolWindow)
```
with:
```kotlin
        val tabs = ChatTabs(project, toolWindow)
        // Registered before the first tab so selecting it is followed too.
        val follower = CheckoutFollower.getInstance(project)
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) follower.follow(event.content.getUserData(ChatTabs.CHAT))
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                // Later: the closed chat leaves Chats when its content is disposed.
                ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed)
            }
        })
```

In `src/main/kotlin/claudechat/ui/ChatTabs.kt` replace lines 3-6:
```kotlin
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import com.intellij.openapi.project.Project
```
with:
```kotlin
import claudechat.follow.CheckoutFollower
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
```
and replace line 29:
```kotlin
        chat.onTitleChanged = { content.displayName = chat.title } // Chat.send runs on the EDT
```
with:
```kotlin
        chat.onTitleChanged = { content.displayName = chat.title } // Chat.send runs on the EDT
        val follower = CheckoutFollower.getInstance(project)
        chat.onCwdChanged = { ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed) }
```

In `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt` (as left by Task 4) replace lines 3-5:
```kotlin
import claudechat.cli.PermissionMode
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
```
with:
```kotlin
import claudechat.cli.PermissionMode
import claudechat.follow.CheckoutFollower
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
```
and replace the line `    override fun createPanel(): DialogPanel = panel {` with:
```kotlin
    override fun apply() {
        super.apply()
        // Only projects whose chat tool window is open have a follower.
        ProjectManager.getInstance().openProjects.forEach { it.serviceIfCreated<CheckoutFollower>()?.refresh() }
    }

    override fun createPanel(): DialogPanel = panel {
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gw test --tests 'claudechat.follow.CheckoutFollowerTest' --tests 'claudechat.session.ChatsTest' --tests 'claudechat.ui.ChatBridgeTest' --tests 'claudechat.ui.WebAssetsTest'`
Expected: PASS — `BUILD SUCCESSFUL`; CheckoutFollowerTest 7, ChatsTest 15, ChatBridgeTest 12, WebAssetsTest 5 tests, 0 failures.

- [ ] **Step 8: Run the whole suite and build the plugin**

Run: `./gw test buildPlugin`
Expected: `BUILD SUCCESSFUL`; 212 tests, 0 failures; `build/distributions/claude-chat-0.1.0.zip` exists.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/claudechat/follow/CheckoutFollower.kt src/main/kotlin/claudechat/session/Chat.kt \
  src/main/kotlin/claudechat/ui/ChatBridge.kt src/main/kotlin/claudechat/ui/ChatTabs.kt \
  src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt \
  src/main/resources/webview/index.html src/main/resources/webview/chat.js src/main/resources/webview/chat.css \
  src/test/kotlin/claudechat/follow/CheckoutFollowerTest.kt src/test/kotlin/claudechat/session/ChatsTest.kt \
  src/test/kotlin/claudechat/ui/ChatBridgeTest.kt src/test/kotlin/claudechat/ui/WebAssetsTest.kt
git commit -m "Follow the selected chat's checkout and show it in the chat header"
```

---

### Task 6: End-to-end check in a sandbox WebStorm

**Files:** none (fix-ups only if a check fails; each fix gets a regression test where the logic is testable outside the UI).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Prepare a scratch git project and launch the sandbox**

```bash
SCRATCH=$(mktemp -d)/v3-check && mkdir -p "$SCRATCH/src" && cd "$SCRATCH"
git init -q
for i in $(seq 1 40); do echo "line $i"; done > src/long.txt
printf 'short\n' > src/short.txt
printf 'only in main\n' > main-only.txt
git add . && git commit -qm init
printf 'not committed, so no worktree has it\n' > main-untracked.txt
git worktree add -q ../v3-outside -b outside
echo "$SCRATCH"
cd /Users/atli/projects/plugin && ./gw runIde
```
In the sandbox WebStorm open the printed folder. Open the Project tool window on its default **Project** pane.

- [ ] **Step 2: Verify each requirement and note the result**

1. Settings > Tools > Claude Chat shows a checked **IDE follows the selected chat's checkout** box. The `main` tab's header shows `IDE: main`. The Project tool window switches to the Scope pane showing **Claude: main**. Settings > Appearance & Behavior > Scopes lists `Claude: main` under Local scopes.
2. New chat > **In a new worktree…** `wt-1`. The new tab's header shows `IDE: wt-1` immediately (predicted folder). Send "Run pwd." so `system/init` reports the folder; the header still says `IDE: wt-1`. A balloon "Worktrees are excluded from this project" appears once.
3. Click **Include and restart** on the balloon (if the sandbox only exits, run `./gw runIde` again and reopen the folder). Help > Find Action > Registry…: `ide.workspace.model.relative.paths.to.exclude.automatically` is now `.worktrees`. `.claude/worktrees/wt-1` is no longer shown as excluded in the Project pane. Recreate the `wt-1` tab if it was not restored: New chat > **In worktree wt-1**.
4. With the `main` tab selected, open `main-untracked.txt`, `src/short.txt` and `src/long.txt` (in that tab order); put the `src/long.txt` caret on line 30, column 3 and keep `src/long.txt` the selected tab; type a character into `src/short.txt` without saving. Select the `wt-1` tab: the tabs now read `main-untracked.txt`, `.claude/worktrees/wt-1/src/short.txt`, `.claude/worktrees/wt-1/src/long.txt` in the same order (`main-untracked.txt` has no counterpart and stays); the `long.txt` caret is on line 30, column 3 and it is still the selected tab; the main `src/short.txt` was saved first (the typed character is on disk, nothing is lost).
5. The Project tool window shows the Scope pane with **Claude: wt-1** and only the worktree's files. Edit > Find > Find in Files… opens with the **Scope** option selected and `Claude: wt-1` in its dropdown. Search Everywhere's scope dropdown (Files tab) lists `Claude: main` and `Claude: wt-1`.
6. Select the `main` tab: editors swap back to the main files at the same positions, the Project view shows **Claude: main**, Find in Files defaults to `Claude: main`, the header says `IDE: main`.
7. Split the editor (Window > Editor Tabs > Split Right) with `src/long.txt` in both splits; select the `wt-1` tab: both splits show the `wt-1` file in place, the previously focused split keeps focus.
8. Switch the Project tool window to the **Project Files** scope (or the Open Files scope). Select `wt-1` and `main` tabs in turn: the Project view stays on what you picked; editors and Find still follow.
9. New chat > **In worktree v3-outside** (the git worktree outside the project): selecting it changes no editor, scope or Project view; its header says `IDE: <current> · <path>/v3-outside is outside this window; open it as a separate project to follow it`, rendered as plain text.
10. Close the `wt-1` tab: Settings > Scopes no longer lists `Claude: wt-1`.
11. Untick the setting and Apply: every chat header hides the indicator; selecting tabs swaps nothing and leaves the Project view alone. Tick it again and Apply: the IDE immediately follows the selected tab.
12. Security spot checks still hold: drag a file onto the chat page — it does not navigate; ask Claude for a markdown link to https://example.com and click it — it opens in the external browser only.

- [ ] **Step 3: Clean up the scratch worktrees and registry value**

```bash
cd <scratch>
git worktree remove --force .claude/worktrees/wt-1
git branch -D worktree-wt-1
git worktree remove --force ../v3-outside
git branch -D outside
```
In the sandbox's Registry reset `ide.workspace.model.relative.paths.to.exclude.automatically` to its default if you want the sandbox back to stock.

- [ ] **Step 4: Fix any failures**

For each failing check, find the root cause (use superpowers:systematic-debugging), add a failing test when the cause is in non-UI code (planner, mapper, patterns, `ProjectViewChoice`, `Indicator`), fix, rerun `./gw test`, and commit with a message describing the fix.

- [ ] **Step 5: Final state**

```bash
git status --short   # expect clean
```

---

## Spec coverage

| Spec requirement | Task |
|---|---|
| Checkout = cwd (predicted until system/init); main by canonical comparison | 1 (`CheckoutMapper.place`), 5 (`Chat.cwd`, `onCwdChanged`) |
| Only checkouts under the project dir are followable; outside → header note | 1, 5 (`Indicator`) |
| Project view scope `Claude: main` / `Claude: <name>`, local named scopes | 2, 4, 5 |
| Switch only from the Project pane or a `Claude:` scope | 4 (`ProjectViewChoice`) |
| Swap open editors, same position, caret clamped, selection kept, save first | 3 |
| Find in Files / Search Everywhere default where public API allows | 4 (`SearchDefaults`; Search Everywhere: scopes only) |
| Header `IDE: main` / `IDE: <name>`, text only | 5 |
| Re-follow when the selected chat's cwd changes | 5 (`Chat.onCwdChanged` → `CheckoutFollower.refresh`) |
| Setting, default on, Settings > Tools > Claude Chat | 4, 5 (`apply()` refresh) |
| Back to main restores `Claude: main` and swaps back | 5 (`testSelectingMainAgainSwapsBack`) |
| `.claude/worktrees` not excluded by the plugin | 2 (`AutoExclude` only offers to remove the platform's own exclusion) |
