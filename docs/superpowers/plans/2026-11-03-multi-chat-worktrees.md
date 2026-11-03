# Claude Chat v2 — Multiple Chats, Worktrees and Message Recall Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run several Claude chats in parallel as tool-window tabs, each optionally in its own git worktree, and recall previously sent messages with ArrowUp/ArrowDown instead of the History dropdown.

**Architecture:** `ChatSession` learns its folder (`cwd`) from `system/init` and can launch `claude --worktree <name>`. The single-chat `SessionManager` is replaced by a project service `Chats` holding many `Chat`s (each: id, label, cwd, its own `ChatSession`, items, attached view). The tool window shows one closeable `Content` per chat; each tab has its own JCEF `ChatPanel` and a `ChatBridge` bound to that one chat. A "New chat" popup (title-bar `+` and the page button) offers project / new worktree / existing worktrees / resume, using a pure `git worktree list --porcelain` parser behind an injectable runner. `EditTracker` keeps per-chat cursors over absolute saved paths, `FileLinker` resolves against the chat's cwd, and a persistent project service `PromptHistory` feeds Up/Down recall in `chat.js`.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, WebStorm 2026.2.3 (build 262) via `local(...)`, JBR 25, JCEF (`bundledPlugin("com.intellij.modules.jcef")`), platform-bundled `kotlinx.serialization.json` (element API only), JUnit 4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-11-03-multi-chat-worktrees-design.md` (extends `docs/superpowers/specs/2026-11-03-claude-chat-webstorm-design.md`; everything in v1 still applies unless the v2 spec changes it).

## Global Constraints

- Repo `/Users/atli/projects/plugin`, branch `feature/claude-chat` (starts at `6432f4d`). Commit on this branch after every task.
- No system Java: run Gradle only through `./gw` (sets `JAVA_HOME` to WebStorm's JBR 25). Examples: `./gw test --tests 'claudechat.session.ChatsTest'`, `./gw test buildPlugin`.
- Target `/Applications/WebStorm.app` (2026.2.3, build 262); `sinceBuild = "262"`. Kotlin package root `claudechat`; plugin id `local.claudechat`; tool window id `Claude Chat`.
- JSON: only the `kotlinx.serialization.json` element API. No serialization compiler plugin, no new dependencies.
- CLI command (claude 2.1.317): `claude -p --input-format stream-json --output-format stream-json --include-partial-messages --verbose --permission-prompt-tool stdio [--permission-mode <mode>] [--worktree <name>] [--resume <id>] [extra args]`.
- `claude -w <name>` / `--worktree <name>` creates a git worktree at `<project>/.claude/worktrees/<name>` on a new branch `worktree-<name>` and runs the session there. The `system/init` message's `cwd` is the worktree path.
- Sessions are stored per cwd: `~/.claude/projects/<cwd with every [^A-Za-z0-9] replaced by '-'>/<id>.jsonl` (`CLAUDE_CONFIG_DIR` overrides `~/.claude`). A worktree session's transcript lives under its worktree path.
- One native content tab per chat (IntelliJ `ContentManager`, closeable tabs); each tab owns its own JCEF page, `ChatSession` and `claude` process. Closing a tab disposes its session (stops the process).
- Tab title: worktree name or `main`, plus `·` and the first ~30 characters of the first user message once sent.
- New-worktree name: default `wt-<n>`; must match `[A-Za-z0-9._-]+`, not an existing worktree name.
- If `git` is unavailable or the project is not a git repo, the "new worktree" and "existing worktree" options are omitted.
- Prompt history: per project, shared by all tabs, most recent last, de-duplicated against the immediately previous entry, capped at 100, persisted across IDE restarts (project-level persistent state). Sending a message appends it.
- Security properties that must survive every task: DOMPurify `SANITIZE` config and the CSP in `index.html`; the `trusted` WeakSet gating `openFile`/permission/diff clicks; the bridge is injected only on the chat page and navigation away from it is blocked (`ChatPanel` `onLoadEnd` + `onBeforeBrowse` with initial-URL capture) without ever blocking the initial load; `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` stripped in `ClaudeProcess`; `openUrl` accepts `http(s)` only.
- Tests never run the real `claude` binary and never touch the real `~/.claude` (use `FakeConnection` and `SessionStore(tempDir)`). Git-dependent logic is tested through an injected lister and a pure porcelain parser.
- `BasePlatformTestCase` shares one light project across tests and project services persist: every test class resets the services it uses in `setUp`. Prefer `.txt` files in new platform tests (opening `.ts` files triggers `AlreadyDisposedException` teardown noise from the bundled TypeScript plugin). In light tests files live on an in-memory file system under the single content root; compute its path with `ProjectRootManager.getInstance(project).contentRoots.first().path` instead of hard-coding it.
- Out of scope: removing or merging worktrees from the plugin; excluding `.claude/worktrees` from IDE indexing; per-tab permission-mode defaults.

## Verified facts used by this plan

- `system/init` (sent at the start of every turn) carries `cwd`, `session_id`, `permissionMode`. `Fx.init` in the test utilities already emits `"cwd":"/proj"`; Task 1 makes it a parameter.
- `git worktree list --porcelain` prints one block per worktree separated by a blank line; the first block is the main worktree. Lines: `worktree <path>`, `HEAD <sha>`, `branch refs/heads/<name>` or `detached`, optionally `bare`, `locked [reason]`, `prunable [reason]`.

## File structure

```
src/main/kotlin/claudechat/cli/CliEvent.kt          MODIFY SystemInit gains cwd
src/main/kotlin/claudechat/cli/ClaudeCommand.kt     MODIFY SessionLaunch gains cwd + worktree; --worktree flag
src/main/kotlin/claudechat/git/GitWorktrees.kt      CREATE Worktree, porcelain parser, GitWorktrees, runGitWorktreeList
src/main/kotlin/claudechat/git/WorktreeNames.kt     CREATE name validation + wt-<n> suggestion
src/main/kotlin/claudechat/session/ChatSession.kt   MODIFY cwd (launch dir, then system/init), worktree on first launch
src/main/kotlin/claudechat/session/SessionStore.kt  MODIFY SessionSummary.folder, listAll(folders)
src/main/kotlin/claudechat/session/ChatSpec.kt      CREATE ChatSpec (how a chat starts) + ChatNames (titles, folder labels)
src/main/kotlin/claudechat/session/Chat.kt          CREATE SessionView (moved) + Chat (one tab's model)
src/main/kotlin/claudechat/session/Chats.kt         CREATE project service registry (replaces SessionManager)
src/main/kotlin/claudechat/session/SessionManager.kt DELETE (Task 5)
src/main/kotlin/claudechat/session/PromptHistory.kt CREATE persistent prompt history
src/main/kotlin/claudechat/ide/EditTracker.kt       MODIFY per-chat cursors over absolute paths + EditNotes
src/main/kotlin/claudechat/ide/FileLinker.kt        MODIFY resolve against a chat's cwd
src/main/kotlin/claudechat/ide/AddToChatAction.kt   MODIFY target the selected tab's chat
src/main/kotlin/claudechat/ui/ChatBridge.kt         MODIFY bound to one Chat; history messages
src/main/kotlin/claudechat/ui/ChatPanel.kt          MODIFY one panel per chat (navigation guard kept verbatim)
src/main/kotlin/claudechat/ui/ChatTabs.kt           CREATE tool-window tabs <-> chats
src/main/kotlin/claudechat/ui/NewChatAction.kt      CREATE title-bar "+" action
src/main/kotlin/claudechat/ui/NewChatChoices.kt     CREATE pure popup model (choices, resume row text)
src/main/kotlin/claudechat/ui/NewChatPopup.kt       CREATE JBPopupFactory popups + worktree name prompt
src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt MODIFY tabs, title action, auto main chat
src/main/resources/META-INF/plugin.xml              MODIFY canCloseContents="true"
src/main/resources/webview/index.html               MODIFY remove History dropdown
src/main/resources/webview/chat.js                  MODIFY remove History dropdown; Up/Down recall
src/test/kotlin/claudechat/testutil/Fx.kt           MODIFY Fx.init(cwd = ...)
src/test/kotlin/claudechat/git/GitWorktreesTest.kt, WorktreeNamesTest.kt          CREATE
src/test/kotlin/claudechat/session/ChatsTest.kt, ChatNamesTest.kt, PromptHistoryTest.kt CREATE
src/test/kotlin/claudechat/ide/EditNotesTest.kt                                   CREATE
src/test/kotlin/claudechat/ui/NewChatChoicesTest.kt                               CREATE
src/test/kotlin/claudechat/session/SessionManagerTest.kt                          DELETE (Task 5)
existing tests under src/test/kotlin/claudechat/{cli,session,ide,ui}             MODIFY as listed per task
```

---

### Task 1: Chat folder from `system/init`, `--worktree` flag, launch directory

**Files:**
- Modify: `src/main/kotlin/claudechat/cli/CliEvent.kt:9,45`
- Modify: `src/main/kotlin/claudechat/cli/ClaudeCommand.kt:14-27`
- Modify: `src/main/kotlin/claudechat/session/ChatSession.kt:29-35,58-81,167-171`
- Modify: `src/main/kotlin/claudechat/session/SessionManager.kt:99,115`
- Modify: `src/test/kotlin/claudechat/testutil/Fx.kt:9-10`
- Test: `src/test/kotlin/claudechat/cli/CliParserTest.kt`, `src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt`, `src/test/kotlin/claudechat/session/ChatSessionTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `CliEvent.SystemInit(sessionId: String, permissionMode: String?, cwd: String?)`
  - `data class SessionLaunch(val resumeId: String?, val permissionMode: PermissionMode?, val cwd: String, val worktree: String? = null)`; `ClaudeCommand.build` emits `--worktree <name>` when `worktree != null`.
  - `ChatSession(connect: (SessionLaunch) -> CliConnection, listener: ChatListener, cwd: String, resumeId: String? = null, initialMode: PermissionMode? = null, worktree: String? = null, log: (String) -> Unit = {})` with `@Volatile var cwd: String` (private set). `cwd` starts as the launch directory and is replaced by a non-blank `system/init` cwd. `worktree` is passed in `SessionLaunch` only until `system/init` has reported a cwd; later launches (restart) use the reported cwd and no worktree.
  - `Fx.init(sessionId: String = "sess-1", mode: String = "default", cwd: String = "/proj")`.

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/claudechat/testutil/Fx.kt` replace lines 9-10:
```kotlin
    fun init(sessionId: String = "sess-1", mode: String = "default", cwd: String = "/proj") =
        """{"type":"system","subtype":"init","cwd":${q(cwd)},"session_id":"$sessionId","tools":["Read","Write"],"permissionMode":"$mode","claude_code_version":"2.1.317","uuid":"u1"}"""
```

In `src/test/kotlin/claudechat/cli/CliParserTest.kt` replace the `systemInit` test (lines 11-13) with:
```kotlin
    @Test fun systemInit() {
        assertEquals(CliEvent.SystemInit("sess-1", "acceptEdits", "/proj"), one(Fx.init(mode = "acceptEdits")))
        assertEquals(
            CliEvent.SystemInit("sess-2", "default", "/proj/.claude/worktrees/wt-1"),
            one(Fx.init("sess-2", cwd = "/proj/.claude/worktrees/wt-1")))
    }

    @Test fun systemInitWithoutCwd() {
        assertEquals(CliEvent.SystemInit("s", null, null), one("""{"type":"system","subtype":"init","session_id":"s"}"""))
    }
```

Replace `src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt` lines 13-18 (the first two tests) with:
```kotlin
    @Test fun newSessionWithoutMode() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null, "/p"), emptyList()))

    @Test fun resumeWithModeAndExtraArgs() = assertEquals(
        base + listOf("--permission-mode", "acceptEdits", "--resume", "abc", "--model", "sonnet"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", PermissionMode.ACCEPT_EDITS, "/p"), listOf("--model", "sonnet")))

    @Test fun newWorktree() = assertEquals(
        base + listOf("--permission-mode", "plan", "--worktree", "wt-1"),
        ClaudeCommand.build("/bin/claude", SessionLaunch(null, PermissionMode.PLAN, "/p", "wt-1"), emptyList()))

    @Test fun cwdIsNotAnArgument() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null, "/somewhere/else"), emptyList()))
```

In `src/test/kotlin/claudechat/session/ChatSessionTest.kt`:

Replace the `session` helper (lines 24-34) with:
```kotlin
    private fun session(
        resumeId: String? = null,
        mode: PermissionMode? = null,
        failConnect: Boolean = false,
        cwd: String = "/proj",
        worktree: String? = null,
    ) =
        ChatSession(
            connect = { launch ->
                if (failConnect) throw IllegalStateException("claude not found")
                launches += launch
                FakeConnection().also { connections += it }
            },
            listener = listener,
            cwd = cwd,
            resumeId = resumeId,
            initialMode = mode,
            worktree = worktree,
        ).also { it.start() }
```

Replace line 236:
```kotlin
        assertEquals(SessionLaunch("sess-7", PermissionMode.PLAN, "/proj"), launches.last())
```

Replace line 259:
```kotlin
        assertEquals(SessionLaunch("abc", PermissionMode.AUTO, "/proj"), launches.single())
```

Replace line 272:
```kotlin
        val s = ChatSession(connect = { bad }, listener = listener, cwd = "/proj")
```

Add before the final closing `}` of the class:
```kotlin

    // --- folder ---

    @Test fun firstLaunchUsesLaunchDirAndWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        assertEquals(SessionLaunch(null, null, "/proj", "wt-1"), launches.single())
        assertEquals("/proj", s.cwd)
    }

    @Test fun systemInitCwdReplacesLaunchDirAndRestartDropsWorktree() {
        val s = session(cwd = "/proj", worktree = "wt-1")
        conn.emit(Fx.init("sess-3", "default", cwd = "/proj/.claude/worktrees/wt-1"))
        assertEquals("/proj/.claude/worktrees/wt-1", s.cwd)
        conn.exit(1, "")
        s.restart()
        assertEquals(
            SessionLaunch("sess-3", PermissionMode.DEFAULT, "/proj/.claude/worktrees/wt-1", null),
            launches.last())
    }

    @Test fun blankInitCwdKeepsLaunchDir() {
        val s = session(cwd = "/proj")
        conn.emit("""{"type":"system","subtype":"init","cwd":"","session_id":"s1","permissionMode":"default"}""")
        assertEquals("/proj", s.cwd)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.cli.*' --tests 'claudechat.session.ChatSessionTest'`
Expected: BUILD FAILED in `compileTestKotlin` (errors such as "Too many arguments for constructor SessionLaunch", "No parameter with name 'cwd' found").

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/cli/CliEvent.kt` line 9:
```kotlin
    data class SystemInit(val sessionId: String, val permissionMode: String?, val cwd: String?) : CliEvent
```
and line 45 (inside `parse`, the `"system"` branch):
```kotlin
                if (obj.str("subtype") == "init") listOf(CliEvent.SystemInit(obj.str("session_id") ?: "", obj.str("permissionMode"), obj.str("cwd")))
```

`src/main/kotlin/claudechat/cli/ClaudeCommand.kt` replace lines 14-27 with:
```kotlin
/**
 * What a new CLI process should start as. [cwd] is the process directory; [worktree] asks claude to
 * create `<cwd>/.claude/worktrees/<name>` on branch `worktree-<name>` and run there.
 */
data class SessionLaunch(
    val resumeId: String?,
    val permissionMode: PermissionMode?,
    val cwd: String,
    val worktree: String? = null,
)

object ClaudeCommand {
    fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> = buildList {
        add(binary)
        addAll(listOf(
            "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
        ))
        launch.permissionMode?.let { add("--permission-mode"); add(it.cli) }
        launch.worktree?.let { add("--worktree"); add(it) }
        launch.resumeId?.let { add("--resume"); add(it) }
        addAll(extraArgs)
    }
}
```

`src/main/kotlin/claudechat/session/ChatSession.kt`:

Replace the constructor and the property block (lines 29-43, from `class ChatSession(` through `var state ... private set`) with:
```kotlin
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    cwd: String,
    resumeId: String? = null,
    initialMode: PermissionMode? = null,
    private val worktree: String? = null,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()

    var sessionId: String? = resumeId
        private set
    var mode: PermissionMode? = initialMode
        private set
    var state: SessionState = SessionState.IDLE
        private set

    /** The folder claude runs in: the launch directory until system/init reports the real one (a new worktree's path). */
    @Volatile var cwd: String = cwd
        private set
    /** True once system/init reported a cwd; from then on restarts run there instead of creating the worktree again. */
    private var cwdReported = false
```

In `start()`, replace the line `val conn = connect(SessionLaunch(sessionId, mode))` with:
```kotlin
                val conn = connect(SessionLaunch(sessionId, mode, cwd, if (cwdReported) null else worktree))
```

In `handle`, replace the `is CliEvent.SystemInit -> { ... }` branch with:
```kotlin
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) sessionId = event.sessionId
                PermissionMode.fromCli(event.permissionMode)?.let { mode = it }
                event.cwd?.takeIf { it.isNotBlank() }?.let { cwd = it; cwdReported = true }
                notifyState()
            }
```

`src/main/kotlin/claudechat/session/SessionManager.kt` line 99:
```kotlin
        val session = ChatSession(connector, listener, cwd(), resumeId, defaultMode(), log = { LOG.info(it) })
```
and line 115:
```kotlin
        return ClaudeProcess(command, File(launch.cwd), env)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.cli.*' --tests 'claudechat.session.ChatSessionTest'`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gw test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/claudechat/cli/CliEvent.kt src/main/kotlin/claudechat/cli/ClaudeCommand.kt \
  src/main/kotlin/claudechat/session/ChatSession.kt src/main/kotlin/claudechat/session/SessionManager.kt \
  src/test/kotlin/claudechat/testutil/Fx.kt src/test/kotlin/claudechat/cli/CliParserTest.kt \
  src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt src/test/kotlin/claudechat/session/ChatSessionTest.kt
git commit -m "Track the chat folder from system/init and support --worktree launches"
```

---

### Task 2: Git worktree discovery and worktree names

**Files:**
- Create: `src/main/kotlin/claudechat/git/GitWorktrees.kt`
- Create: `src/main/kotlin/claudechat/git/WorktreeNames.kt`
- Test: `src/test/kotlin/claudechat/git/GitWorktreesTest.kt`, `src/test/kotlin/claudechat/git/WorktreeNamesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (package `claudechat.git`):
  - `data class Worktree(val path: String, val branch: String?, val isMain: Boolean)` with `val name: String` (last path segment). `branch` is the short name (`refs/heads/` stripped), null when detached.
  - `object WorktreePorcelain { fun parse(output: String): List<Worktree> }` — first block is main; `bare` blocks dropped.
  - `class GitWorktrees(projectDir: String, lister: (File) -> String? = ::runGitWorktreeList)` with `fun list(): List<Worktree>?` (null = git missing / not a repo; runs a process, never call on the EDT), `fun claudeWorktreeDirs(): List<String>`, `fun knownFolders(worktrees: List<Worktree>?): List<String>` (project dir, git worktree paths, `<project>/.claude/worktrees/*` dirs; distinct, in that order), `fun existingNames(worktrees: List<Worktree>?): Set<String>`.
  - top-level `fun runGitWorktreeList(dir: File): String?`.
  - `object WorktreeNames { fun error(name: String, existing: Set<String>): String?; fun suggest(existing: Set<String>): String }`.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/git/GitWorktreesTest.kt`:
```kotlin
package claudechat.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitWorktreesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = """
        worktree /Users/me/proj
        HEAD 1111111111111111111111111111111111111111
        branch refs/heads/main

        worktree /Users/me/proj/.claude/worktrees/wt-1
        HEAD 2222222222222222222222222222222222222222
        branch refs/heads/worktree-wt-1

        worktree /Users/me/other-checkout
        HEAD 3333333333333333333333333333333333333333
        detached
        locked

    """.trimIndent()

    @Test fun parsesMainLinkedAndDetachedWorktrees() = assertEquals(
        listOf(
            Worktree("/Users/me/proj", "main", isMain = true),
            Worktree("/Users/me/proj/.claude/worktrees/wt-1", "worktree-wt-1", isMain = false),
            Worktree("/Users/me/other-checkout", null, isMain = false),
        ),
        WorktreePorcelain.parse(sample))

    @Test fun bareMainIsDroppedButStillCountsAsFirst() {
        val out = "worktree /r/repo.git\nbare\n\nworktree /r/main\nHEAD abc\nbranch refs/heads/main\n"
        assertEquals(listOf(Worktree("/r/main", "main", isMain = false)), WorktreePorcelain.parse(out))
    }

    @Test fun windowsLineEndingsAndEmptyOutput() {
        assertEquals(listOf(Worktree("/p", "main", true)), WorktreePorcelain.parse("worktree /p\r\nbranch refs/heads/main\r\n\r\n"))
        assertEquals(emptyList<Worktree>(), WorktreePorcelain.parse(""))
    }

    @Test fun nameIsLastPathSegment() = assertEquals("wt-1", Worktree("/p/.claude/worktrees/wt-1/", null, false).name)

    @Test fun listIsNullWhenGitFails() = assertNull(GitWorktrees("/p") { null }.list())

    @Test fun listRunsListerInProjectDir() {
        var asked: File? = null
        val worktrees = GitWorktrees("/Users/me/proj") { asked = it; sample }.list()
        assertEquals(File("/Users/me/proj"), asked)
        assertEquals(3, worktrees!!.size)
    }

    @Test fun knownFoldersAndExistingNamesCombineGitAndClaudeDirs() {
        val proj = tmp.newFolder("proj")
        File(proj, ".claude/worktrees/wt-1").mkdirs()
        File(proj, ".claude/worktrees/wt-2").mkdirs()
        File(proj, ".claude/worktrees/stray.txt").writeText("")
        val git = GitWorktrees(proj.path) { null }
        val listed = listOf(
            Worktree(proj.path, "main", true),
            Worktree("${proj.path}/.claude/worktrees/wt-1", "worktree-wt-1", false),
            Worktree("/elsewhere/co", null, false),
        )
        assertEquals(
            listOf(proj.path, "${proj.path}/.claude/worktrees/wt-1", "/elsewhere/co", "${proj.path}/.claude/worktrees/wt-2"),
            git.knownFolders(listed))
        assertEquals(setOf("wt-1", "co", "wt-2"), git.existingNames(listed))
        assertEquals(
            listOf(proj.path, "${proj.path}/.claude/worktrees/wt-1", "${proj.path}/.claude/worktrees/wt-2"),
            git.knownFolders(null))
    }

    @Test fun knownFoldersWithoutClaudeDir() {
        val proj = tmp.newFolder("plain")
        assertEquals(listOf(proj.path), GitWorktrees(proj.path) { null }.knownFolders(null))
        assertEquals(emptySet<String>(), GitWorktrees(proj.path) { null }.existingNames(null))
    }

    @Test fun realGitReturnsNullOutsideARepository() = assertNull(runGitWorktreeList(tmp.newFolder("not-a-repo")))
}
```

`src/test/kotlin/claudechat/git/WorktreeNamesTest.kt`:
```kotlin
package claudechat.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorktreeNamesTest {
    @Test fun acceptsValidNewNames() {
        assertNull(WorktreeNames.error("feature_1.x-y", setOf("wt-1")))
        assertNull(WorktreeNames.error("wt-2", setOf("wt-1")))
    }

    @Test fun rejectsInvalidNames() {
        for (bad in listOf("", "has space", "a/b", "ü", "-x", ".", "..", "a..b", "x.", "x.lock")) {
            assertNotNull("expected '$bad' to be rejected", WorktreeNames.error(bad, emptySet()))
        }
    }

    @Test fun rejectsExistingName() =
        assertEquals("A worktree named 'wt-1' already exists.", WorktreeNames.error("wt-1", setOf("wt-1")))

    @Test fun suggestsFirstFreeWtName() {
        assertEquals("wt-1", WorktreeNames.suggest(emptySet()))
        assertEquals("wt-3", WorktreeNames.suggest(setOf("wt-1", "wt-2", "other")))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.git.*'`
Expected: BUILD FAILED in `compileTestKotlin` ("Unresolved reference 'WorktreePorcelain'", "Unresolved reference 'WorktreeNames'").

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/git/GitWorktrees.kt`:
```kotlin
package claudechat.git

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One entry of `git worktree list --porcelain`. [branch] is the short branch name, null when detached. */
data class Worktree(val path: String, val branch: String?, val isMain: Boolean) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

object WorktreePorcelain {
    /** The first block is the main worktree. Bare entries are dropped (nothing can run there). */
    fun parse(output: String): List<Worktree> =
        output.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { block -> block.lines().filter { it.isNotBlank() } }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull { index, lines ->
                val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ")
                    ?: return@mapIndexedNotNull null
                if ("bare" in lines) return@mapIndexedNotNull null
                val branch = lines.firstOrNull { it.startsWith("branch ") }
                    ?.removePrefix("branch ")?.removePrefix("refs/heads/")
                Worktree(path, branch, isMain = index == 0)
            }
}

/**
 * The project's worktrees. [lister] runs `git worktree list --porcelain` in a directory and returns its
 * stdout, or null when git is missing or the directory is not in a repository (tests inject a fake).
 */
class GitWorktrees(
    private val projectDir: String,
    private val lister: (File) -> String? = ::runGitWorktreeList,
) {
    /** Every worktree including the main one; null without git. Runs a process: never call on the EDT. */
    fun list(): List<Worktree>? = lister(File(projectDir))?.let(WorktreePorcelain::parse)

    /** Folders claude --worktree created under `<project>/.claude/worktrees`, sorted. */
    fun claudeWorktreeDirs(): List<String> =
        File(projectDir, ".claude/worktrees").listFiles { f -> f.isDirectory }.orEmpty().map { it.path }.sorted()

    /** Folders whose past sessions can be resumed: the project, every git worktree, every `.claude/worktrees/*` dir. */
    fun knownFolders(worktrees: List<Worktree>?): List<String> =
        (listOf(projectDir) + worktrees.orEmpty().map { it.path } + claudeWorktreeDirs()).distinct()

    /** Names a new worktree must not reuse. */
    fun existingNames(worktrees: List<Worktree>?): Set<String> =
        (worktrees.orEmpty().filterNot { it.isMain }.map { it.name } + claudeWorktreeDirs().map { it.substringAfterLast('/') }).toSet()
}

/** Runs `git worktree list --porcelain` in [dir]; null if git is missing, fails, or takes longer than 5 seconds. */
fun runGitWorktreeList(dir: File): String? = try {
    val process = ProcessBuilder("git", "worktree", "list", "--porcelain")
        .directory(dir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    process.outputStream.close()
    val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
    when {
        !process.waitFor(5, TimeUnit.SECONDS) -> { process.destroyForcibly(); null }
        process.exitValue() != 0 -> null
        else -> out
    }
} catch (_: IOException) {
    null
}
```

`src/main/kotlin/claudechat/git/WorktreeNames.kt`:
```kotlin
package claudechat.git

/** Names for `claude --worktree <name>` (folder `.claude/worktrees/<name>`, branch `worktree-<name>`). */
object WorktreeNames {
    private val VALID = Regex("[A-Za-z0-9._-]+")

    /** Why [name] cannot be used, or null when it can. */
    fun error(name: String, existing: Set<String>): String? = when {
        name.isEmpty() -> "Enter a name."
        !VALID.matches(name) -> "Use only letters, digits, '.', '_' and '-'."
        // claude would read a leading '-' as an option.
        name.startsWith("-") -> "The name cannot start with '-'."
        // git refuses these in the branch name worktree-<name> (and '.'/'..' are not folders we can create).
        name.contains("..") || name.endsWith(".") || name.endsWith(".lock") -> "Git cannot use this name for a branch."
        name in existing -> "A worktree named '$name' already exists."
        else -> null
    }

    fun suggest(existing: Set<String>): String =
        generateSequence(1) { it + 1 }.map { "wt-$it" }.first { it !in existing }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.git.*'`
Expected: BUILD SUCCESSFUL, 13 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/git src/test/kotlin/claudechat/git
git commit -m "Discover git worktrees and validate new worktree names"
```

---

### Task 3: Past sessions across project and worktree folders

**Files:**
- Modify: `src/main/kotlin/claudechat/session/SessionStore.kt:12,18-24`
- Test: `src/test/kotlin/claudechat/session/SessionStoreTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long, val folder: String)`; `SessionStore.list(cwd, limit = 30)` sets `folder = cwd`; `SessionStore.listAll(folders: List<String>, limit: Int = 50): List<SessionSummary>` — newest first across folders, folders that encode to the same project dir read once.

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/claudechat/session/SessionStoreTest.kt`, replace the `write` helper (lines 14-17) with:
```kotlin
    private fun write(store: SessionStore, id: String, modified: Long, vararg lines: String): File =
        writeIn(store, cwd, id, modified, *lines)

    private fun writeIn(store: SessionStore, folder: String, id: String, modified: Long, vararg lines: String): File {
        val dir = store.projectDir(folder).apply { mkdirs() }
        return File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n")); setLastModified(modified) }
    }
```

Replace lines 34-36 (the expectation in `listsNewestFirstWithFirstRealUserMessageAsTitle`) with:
```kotlin
        assertEquals(
            listOf(SessionSummary("new", "second question", 2_000_000, cwd), SessionSummary("old", "first question", 1_000_000, cwd)),
            store.list(cwd))
```

Add before the final closing `}`:
```kotlin

    @Test fun listAllMergesFoldersNewestFirst() {
        val store = SessionStore(tmp.root)
        val wt = "$cwd/.claude/worktrees/wt-1"
        writeIn(store, cwd, "a", 1_000_000, user("in project"))
        writeIn(store, wt, "b", 3_000_000, user("in worktree"))
        writeIn(store, cwd, "c", 2_000_000, user("later in project"))
        assertEquals(
            listOf(
                SessionSummary("b", "in worktree", 3_000_000, wt),
                SessionSummary("c", "later in project", 2_000_000, cwd),
                SessionSummary("a", "in project", 1_000_000, cwd),
            ),
            store.listAll(listOf(cwd, wt, cwd, "/nowhere")))
    }

    @Test fun listAllHonoursLimit() {
        val store = SessionStore(tmp.root)
        writeIn(store, cwd, "a", 1_000_000, user("one"))
        writeIn(store, "$cwd/.claude/worktrees/wt-1", "b", 2_000_000, user("two"))
        assertEquals(listOf("b"), store.listAll(listOf(cwd, "$cwd/.claude/worktrees/wt-1"), limit = 1).map { it.id })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.SessionStoreTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Too many arguments for constructor SessionSummary", "Unresolved reference 'listAll'").

- [ ] **Step 3: Implement**

In `src/main/kotlin/claudechat/session/SessionStore.kt` replace line 12:
```kotlin
/** A past session; [folder] is the cwd it ran in (its transcript lives under that folder's project dir). */
data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long, val folder: String)
```

Replace `list` (lines 18-24) with:
```kotlin
    fun list(cwd: String, limit: Int = 30): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> firstUserText(f)?.let { SessionSummary(f.nameWithoutExtension, oneLine(it), f.lastModified(), cwd) } }
            .take(limit)
            .toList()

    /** Sessions of every folder, newest first. Folders that map to the same project dir are read once. */
    fun listAll(folders: List<String>, limit: Int = 50): List<SessionSummary> =
        folders.distinctBy { projectDir(it) }
            .flatMap { list(it, limit) }
            .sortedByDescending { it.modifiedMillis }
            .take(limit)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.SessionStoreTest'`
Expected: BUILD SUCCESSFUL, 6 tests PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gw test`
Expected: BUILD SUCCESSFUL (`ChatBridge` and `SessionManager` still compile: they only read `id`, `title`, `modifiedMillis`).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/claudechat/session/SessionStore.kt src/test/kotlin/claudechat/session/SessionStoreTest.kt
git commit -m "List past sessions across project and worktree folders"
```

---

### Task 4: Multi-chat registry (`Chats`, `Chat`, `ChatSpec`)

`Chats` is added next to the old `SessionManager` (which keeps working until Task 5 deletes it), so this task touches no UI code.

**Files:**
- Create: `src/main/kotlin/claudechat/session/ChatSpec.kt`
- Create: `src/main/kotlin/claudechat/session/Chat.kt`
- Create: `src/main/kotlin/claudechat/session/Chats.kt`
- Modify: `src/main/kotlin/claudechat/session/SessionManager.kt:20-31` (move `SessionView` and `ClaudeNotFoundException` out)
- Test: `src/test/kotlin/claudechat/session/ChatNamesTest.kt`, `src/test/kotlin/claudechat/session/ChatsTest.kt`

**Interfaces:**
- Consumes: `ChatSession(connect, listener, cwd, resumeId, initialMode, worktree, log)` and `ChatSession.cwd` (Task 1); `SessionStore.transcript`, `SessionSummary(id, title, modifiedMillis, folder)` (Task 3); `EditTracker.consumeNote()` (v1 signature; Task 7 changes it).
- Produces (package `claudechat.session`):
  - `data class ChatSpec(val label: String, val cwd: String, val worktree: String? = null, val resumeId: String? = null)` with companion `MAIN = "main"`, `inProject(projectDir)`, `newWorktree(projectDir, name)`, `existingWorktree(path)`, `resume(projectDir, session: SessionSummary)`.
  - `object ChatNames { fun tabTitle(label: String, firstMessage: String?): String; fun folderLabel(folder: String, projectDir: String): String; fun lastSegment(path: String): String }`.
  - `interface SessionView` (unchanged signature, moved to `Chat.kt`).
  - `class Chat(id: Int, spec: ChatSpec, history: List<ChatItem>, connector: (SessionLaunch) -> CliConnection, initialMode: PermissionMode?, editNote: (Chat) -> String?, log: (String) -> Unit = {})` with `val id: Int`, `val label: String`, `val session: ChatSession`, `val cwd: String`, `val title: String`, `val firstMessage: String?`, `var onTitleChanged: (() -> Unit)?`, `fun start()`, `fun attach(view: SessionView)`, `fun detach(view: SessionView)`, `fun insert(text: String)`, `fun send(text: String)` (EDT only), `fun dispose()`.
  - `@Service(PROJECT) class Chats` with `var connector`, `var store: SessionStore`, `val projectDir: String`, `fun all(): List<Chat>`, `fun mainSpec(): ChatSpec`, `fun open(spec: ChatSpec): Chat` (creates and starts), `fun close(chat: Chat)`, `fun closeAll()`, `companion fun getInstance(project): Chats`.
  - `class ClaudeNotFoundException` (moved to `Chats.kt`).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/session/ChatNamesTest.kt`:
```kotlin
package claudechat.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatNamesTest {
    @Test fun titleIsLabelUntilAMessageIsSent() = assertEquals("main", ChatNames.tabTitle("main", null))

    @Test fun titleAddsShortFirstMessageWithWhitespaceCollapsed() =
        assertEquals("wt-1 · fix the build", ChatNames.tabTitle("wt-1", "fix the\n  build"))

    @Test fun longFirstMessageIsCutAtThirtyCharacters() = assertEquals(
        "main · Fix the flaky login test in th…",
        ChatNames.tabTitle("main", "Fix the flaky login test in the auth module"))

    @Test fun blankMessageKeepsLabel() = assertEquals("main", ChatNames.tabTitle("main", "   "))

    @Test fun folderLabel() {
        assertEquals("main", ChatNames.folderLabel("/p/", "/p"))
        assertEquals("wt-1", ChatNames.folderLabel("/p/.claude/worktrees/wt-1", "/p"))
    }

    @Test fun specs() {
        assertEquals(ChatSpec("main", "/p"), ChatSpec.inProject("/p"))
        assertEquals(ChatSpec("wt-2", "/p", worktree = "wt-2"), ChatSpec.newWorktree("/p", "wt-2"))
        assertEquals(ChatSpec("co", "/x/co"), ChatSpec.existingWorktree("/x/co"))
        assertEquals(
            ChatSpec("wt-1", "/p/.claude/worktrees/wt-1", resumeId = "s1"),
            ChatSpec.resume("/p", SessionSummary("s1", "t", 0, "/p/.claude/worktrees/wt-1")))
        assertEquals(ChatSpec("main", "/p", resumeId = "s2"), ChatSpec.resume("/p", SessionSummary("s2", "t", 0, "/p")))
    }
}
```

`src/test/kotlin/claudechat/session/ChatsTest.kt`:
```kotlin
package claudechat.session

import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.ide.EditTracker
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class ChatsTest : BasePlatformTestCase() {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var home: File
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    private class RecordingView : SessionView {
        val resets = mutableListOf<List<ChatItem>>()
        val items = mutableListOf<ChatItem>()
        val states = mutableListOf<SessionState>()
        val inserted = mutableListOf<String>()
        override fun reset(items: List<ChatItem>) { resets += items }
        override fun item(item: ChatItem) { items += item }
        override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) { states += state }
        override fun insert(text: String) { inserted += text }
    }

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        chats.store = SessionStore(home)
        EditTracker.getInstance(project).consumeNote() // drop saves recorded by earlier tests
    }

    override fun tearDown() {
        try { chats.closeAll(); home.deleteRecursively() } finally { super.tearDown() }
    }

    fun testOpenLaunchesInTheSpecFolder() {
        val chat = chats.open(ChatSpec.inProject("/work/proj"))
        assertEquals("/work/proj", launches.single().cwd)
        assertNull(launches.single().worktree)
        assertEquals("/work/proj", chat.cwd)
        assertEquals("main", chat.title)
        assertEquals(listOf(chat), chats.all())
    }

    fun testNewWorktreeChatPassesNameAndAdoptsReportedFolder() {
        val chat = chats.open(ChatSpec.newWorktree("/work/proj", "wt-1"))
        assertEquals("wt-1", launches.single().worktree)
        assertEquals("/work/proj", launches.single().cwd)
        connections.single().emit(Fx.init(cwd = "/work/proj/.claude/worktrees/wt-1"))
        assertEquals("/work/proj/.claude/worktrees/wt-1", chat.cwd)
        assertEquals("wt-1", chat.title)
    }

    fun testChatsAreIndependent() {
        val a = chats.open(ChatSpec.inProject(root))
        val b = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1"))
        val viewA = RecordingView().also(a::attach)
        val viewB = RecordingView().also(b::attach)
        a.send("hello")
        assertEquals(listOf("hello"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().isEmpty())
        assertTrue(viewA.items.any { it is ChatItem.UserText })
        assertTrue(viewB.items.isEmpty())
        assertEquals(SessionState.IDLE, b.session.state)
    }

    fun testCloseStopsOnlyThatChat() {
        val a = chats.open(ChatSpec.inProject(root))
        val b = chats.open(ChatSpec.inProject(root))
        chats.close(a)
        assertTrue(connections[0].stopped)
        assertFalse(connections[1].stopped)
        assertEquals(listOf(b), chats.all())
    }

    fun testAttachReplaysItemsAndState() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("hi"), view.resets.single().filterIsInstance<ChatItem.UserText>().map { it.text })
        assertEquals(SessionState.RUNNING, view.states.last())
    }

    fun testInsertBeforeAttachIsDeliveredOnAttach() {
        val chat = chats.open(ChatSpec.inProject(root))
        chat.insert("@src/a.txt#L1-2")
        val view = RecordingView()
        chat.attach(view)
        assertEquals(listOf("@src/a.txt#L1-2"), view.inserted)
        chat.insert("@b.txt")
        assertEquals(listOf("@src/a.txt#L1-2", "@b.txt"), view.inserted)
    }

    fun testResumeLoadsHistoryFromTheSessionFolder() {
        val folder = "/work/proj/.claude/worktrees/wt-1"
        File(chats.store.projectDir(folder).apply { mkdirs() }, "abc.jsonl")
            .writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val chat = chats.open(ChatSpec.resume("/work/proj", SessionSummary("abc", "earlier", 0, folder)))
        assertEquals("abc", launches.single().resumeId)
        assertEquals(folder, launches.single().cwd)
        val view = RecordingView().also(chat::attach)
        assertEquals(listOf(ChatItem.UserText("h-0", "earlier")), view.resets.single())
        assertEquals("wt-1 · earlier", chat.title)
    }

    fun testFirstSentMessageSetsTitleOnce() {
        val chat = chats.open(ChatSpec.inProject(root))
        var changes = 0
        chat.onTitleChanged = { changes++ }
        chat.send("Fix the flaky login test in the auth module")
        chat.send("and another")
        assertEquals("main · Fix the flaky login test in th…", chat.title)
        assertEquals(1, changes)
    }

    fun testDeadChatIgnoresSend() {
        val chat = chats.open(ChatSpec.inProject(root))
        connections.single().exit(1, "gone")
        chat.send("lost")
        assertTrue(connections.single().userMessages().isEmpty())
        assertEquals("main", chat.title)
    }

    fun testSendPrefixesEditNote() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        val content = connections.single().userMessages().single()
        assertTrue(content, content.startsWith("[IDE] Since your last turn the user edited these files: a.txt."))
        assertTrue(content.endsWith("\n\nhello"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.ChatNamesTest' --tests 'claudechat.session.ChatsTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Unresolved reference 'ChatNames'", "Unresolved reference 'Chats'").

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/session/ChatSpec.kt`:
```kotlin
package claudechat.session

/**
 * How a chat starts. [cwd] is the launch directory. For a new worktree it is the project and [worktree]
 * names the worktree claude creates (the chat then moves to the folder system/init reports).
 */
data class ChatSpec(val label: String, val cwd: String, val worktree: String? = null, val resumeId: String? = null) {
    companion object {
        const val MAIN = "main"

        fun inProject(projectDir: String) = ChatSpec(MAIN, projectDir)

        fun newWorktree(projectDir: String, name: String) = ChatSpec(name, projectDir, worktree = name)

        fun existingWorktree(path: String) = ChatSpec(ChatNames.lastSegment(path), path)

        fun resume(projectDir: String, session: SessionSummary) =
            ChatSpec(ChatNames.folderLabel(session.folder, projectDir), session.folder, resumeId = session.id)
    }
}

object ChatNames {
    private const val TITLE_CHARS = 30

    /** `label` alone, or `label · <first ~30 characters of the first message>`. */
    fun tabTitle(label: String, firstMessage: String?): String {
        val text = firstMessage?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (text.isEmpty()) return label
        val short = if (text.length > TITLE_CHARS) text.take(TITLE_CHARS).trimEnd() + "…" else text
        return "$label · $short"
    }

    /** `main` for the project folder, otherwise the folder's name (a worktree name). */
    fun folderLabel(folder: String, projectDir: String): String =
        if (folder.trimEnd('/') == projectDir.trimEnd('/')) ChatSpec.MAIN else lastSegment(folder)

    fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
```

`src/main/kotlin/claudechat/session/Chat.kt`:
```kotlin
package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch

/** Receives one chat's updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)
}

/** One chat tab's model: its own [ChatSession] and claude process, the items shown so far, and the page showing them. */
class Chat(
    val id: Int,
    spec: ChatSpec,
    history: List<ChatItem>,
    connector: (SessionLaunch) -> CliConnection,
    initialMode: PermissionMode?,
    private val editNote: (Chat) -> String?,
    log: (String) -> Unit = {},
) {
    val label: String = spec.label

    private val lock = Any()
    private val items = LinkedHashMap<String, ChatItem>().apply { history.forEach { put(it.id, it) } }
    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null

    /** The first message the user sent, or the first one of a resumed transcript. */
    @Volatile var firstMessage: String? = history.filterIsInstance<ChatItem.UserText>().firstOrNull()?.text
        private set

    /** Called on the EDT when [title] changes. */
    @Volatile var onTitleChanged: (() -> Unit)? = null

    private val listener = object : ChatListener {
        override fun onItem(item: ChatItem) {
            synchronized(lock) { items[item.id] = item }
            view?.item(item)
        }

        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
        }
    }

    val session = ChatSession(connector, listener, spec.cwd, spec.resumeId, initialMode, spec.worktree, log)

    /** Absolute folder claude runs in (the worktree path once system/init reported it). */
    val cwd: String get() = session.cwd

    val title: String get() = ChatNames.tabTitle(label, firstMessage)

    fun start() = session.start()

    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else synchronized(lock) { pendingInsert = text }
    }

    /** EDT only (the edit note saves documents). Ignored while the session is dead. */
    fun send(text: String) {
        if (session.state == SessionState.DEAD) return
        if (firstMessage == null) {
            firstMessage = text
            onTitleChanged?.invoke()
        }
        session.send(text, editNote(this))
    }

    fun dispose() = session.dispose()
}
```

`src/main/kotlin/claudechat/session/Chats.kt`:
```kotlin
package claudechat.session

import claudechat.cli.ClaudeBinary
import claudechat.cli.ClaudeCommand
import claudechat.cli.ClaudeProcess
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.ide.EditTracker
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Claude Chat."
)

/** The project's open chats. Each chat owns its own claude process. */
@Service(Service.Level.PROJECT)
class Chats(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    var store: SessionStore = SessionStore.default()

    private val chats = CopyOnWriteArrayList<Chat>()
    private val ids = AtomicInteger()

    val projectDir: String get() = project.basePath ?: System.getProperty("user.home")

    init {
        EditTracker.getInstance(project) // start recording saves before the first message
    }

    fun all(): List<Chat> = chats.toList()

    fun mainSpec(): ChatSpec = ChatSpec.inProject(projectDir)

    /** Creates and starts a chat (a resumed one shows its transcript). EDT. */
    fun open(spec: ChatSpec): Chat {
        val history = spec.resumeId?.let { store.transcript(spec.cwd, it) }.orEmpty()
        val chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { EditTracker.getInstance(project).consumeNote() },
            log = { LOG.info(it) },
        )
        chats += chat
        chat.start()
        return chat
    }

    /** Stops the chat's process. Its transcript stays on disk. */
    fun close(chat: Chat) {
        if (chats.remove(chat)) chat.dispose()
    }

    fun closeAll() = all().forEach(::close)

    private fun defaultMode(): PermissionMode? = PermissionMode.fromCli(ClaudeChatSettings.getInstance().state.defaultMode)

    private fun defaultConnect(launch: SessionLaunch): CliConnection {
        val settings = ClaudeChatSettings.getInstance().state
        val env = EnvironmentUtil.getEnvironmentMap()
        val binary = ClaudeBinary.resolve(settings.claudePath.orEmpty(), env["PATH"], System.getProperty("user.home")) {
            File(it).canExecute()
        } ?: throw ClaudeNotFoundException()
        val command = ClaudeCommand.build(binary, launch, ParametersListUtil.parse(settings.extraArgs.orEmpty()))
        return ClaudeProcess(command, File(launch.cwd), env)
    }

    override fun dispose() = closeAll()

    companion object {
        private val LOG = logger<Chats>()
        fun getInstance(project: Project): Chats = project.service()
    }
}
```

In `src/main/kotlin/claudechat/session/SessionManager.kt` delete lines 20-31 (now defined in `Chat.kt` and `Chats.kt`):
```kotlin
/** Receives chat updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)
}

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Claude Chat."
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.ChatNamesTest' --tests 'claudechat.session.ChatsTest'`
Expected: BUILD SUCCESSFUL, 16 tests PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gw test`
Expected: BUILD SUCCESSFUL (`SessionManagerTest` and `ChatBridgeTest` still pass against the untouched `SessionManager`).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/claudechat/session/ChatSpec.kt src/main/kotlin/claudechat/session/Chat.kt \
  src/main/kotlin/claudechat/session/Chats.kt src/main/kotlin/claudechat/session/SessionManager.kt \
  src/test/kotlin/claudechat/session/ChatNamesTest.kt src/test/kotlin/claudechat/session/ChatsTest.kt
git commit -m "Add a registry of independent chats"
```

---

### Task 5: One tool-window tab per chat

Switches the UI from `SessionManager` to `Chats`, deletes `SessionManager`, removes the v1 History dropdown (resume returns in Task 6 inside the New-chat popup). The `+` title action and the page's "New chat" button open a new `main` tab for now; Task 6 replaces that with the popup.

**Files:**
- Modify: `src/main/kotlin/claudechat/ui/ChatBridge.kt` (rewrite)
- Modify: `src/main/kotlin/claudechat/ui/ChatPanel.kt` (rewrite; navigation guard unchanged)
- Create: `src/main/kotlin/claudechat/ui/ChatTabs.kt`
- Create: `src/main/kotlin/claudechat/ui/NewChatAction.kt`
- Modify: `src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt` (rewrite)
- Modify: `src/main/kotlin/claudechat/ide/AddToChatAction.kt` (rewrite)
- Modify: `src/main/resources/META-INF/plugin.xml:16-17`
- Modify: `src/main/resources/webview/index.html:11`, `src/main/resources/webview/chat.js:8,48,169-176,211-217`
- Delete: `src/main/kotlin/claudechat/session/SessionManager.kt`, `src/test/kotlin/claudechat/session/SessionManagerTest.kt`
- Test: `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt` (rewrite), `src/test/kotlin/claudechat/ui/WebAssetsTest.kt`, `src/test/kotlin/claudechat/ide/MentionTest.kt`

**Interfaces:**
- Consumes: `Chats`, `Chat`, `ChatSpec`, `SessionView` (Task 4).
- Produces:
  - `class ChatBridge(project: Project, chat: Chat, onNewChat: () -> Unit, post: (String) -> Unit) : SessionView { fun handle(json: String) }` — `handle` on the EDT. Page messages: `ready`, `send`, `permission`, `setMode`, `interrupt`, `newChat`, `restart`, `openFile`, `openDiff`, `openUrl`, `resolvePaths`. (`listSessions`/`resume` are gone.)
  - `class ChatPanel(project: Project, chat: Chat, onNewChat: () -> Unit, parent: Disposable) : Disposable` exposing `browser: JBCefBrowser`.
  - `class ChatTabs(project: Project, toolWindow: ToolWindow)` with `fun open(spec: ChatSpec): Chat` (EDT), `fun selectedChat(): Chat?`, companion `CHAT: Key<Chat>`, `fun find(project): ChatTabs?`.
  - `class NewChatAction : DumbAwareAction` (title-bar `+`).
  - `Mention.relativeTo(path: String, dir: String): String?`.

- [ ] **Step 1: Write the failing tests**

Replace `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt` with:
```kotlin
package claudechat.ui

import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.SessionState
import claudechat.testutil.FakeConnection
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject

class ChatBridgeTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private var newChatRequests = 0
    private lateinit var chat: Chat
    private lateinit var bridge: ChatBridge
    private val chats get() = Chats.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    override fun setUp() {
        super.setUp()
        chats.closeAll()
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try { chat.detach(bridge); chats.closeAll() } finally { super.tearDown() }
    }

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeResetAndState() {
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "reset", "state"), types())
        assertEquals("IDLE", posted.last().str("state"))
    }

    fun testSendReachesThisChatsCliOnly() {
        val other = chats.open(ChatSpec("wt-1", "$root/.claude/worktrees/wt-1"))
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertTrue(connections[0].userMessages().single().endsWith("hello"))
        assertTrue(connections[1].userMessages().isEmpty())
        assertEquals("user", posted.last { it.str("type") == "item" }.obj("item")!!.str("kind"))
        assertEquals("RUNNING", posted.last { it.str("type") == "state" }.str("state"))
        assertEquals(SessionState.IDLE, other.session.state)
    }

    fun testSetModeGoesToThisChat() {
        chats.open(ChatSpec.inProject(root))
        bridge.handle("""{"type":"setMode","mode":"plan"}""")
        assertEquals("set_permission_mode", connections[0].sentJson().last().obj("request")!!.str("subtype"))
        assertEquals("initialize", connections[1].sentJson().last().obj("request")!!.str("subtype"))
    }

    fun testNewChatAsksTheTabs() {
        bridge.handle("""{"type":"newChat"}""")
        assertEquals(1, newChatRequests)
        assertTrue(posted.isEmpty())
    }

    fun testResolvePathsReturnsOnlyExistingFiles() {
        val file = myFixture.addFileToProject("src/x.txt", "a").virtualFile
        bridge.handle("""{"type":"resolvePaths","reqId":7,"candidates":["src/x.txt:1","nope.txt"]}""")
        val msg = posted.single()
        assertEquals("resolved", msg.str("type"))
        val paths = msg.obj("paths")!!
        assertEquals(file.url, paths.obj("src/x.txt:1")!!.str("target"))
        assertNull(paths["nope.txt"])
    }

    fun testUnknownMalformedAndRemovedMessagesAreIgnored() {
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        bridge.handle("""{"type":"listSessions"}""")
        bridge.handle("""{"type":"resume","sessionId":"abc"}""")
        assertTrue(posted.isEmpty())
        assertEquals(1, connections.size)
    }
}
```

In `src/test/kotlin/claudechat/ui/WebAssetsTest.kt` add before the final closing `}`:
```kotlin

    @Test fun historyDropdownIsGone() {
        val page = WebAssets.page()
        assertFalse(page.contains("id=\"sessions\""))
        assertFalse(page.contains("listSessions"))
    }
```

In `src/test/kotlin/claudechat/ide/MentionTest.kt` add before the final closing `}`:
```kotlin

    @Test fun relativeToFolder() {
        assertEquals("src/a.ts", Mention.relativeTo("/p/.claude/worktrees/wt-1/src/a.ts", "/p/.claude/worktrees/wt-1"))
        assertEquals(".claude/worktrees/wt-1/a.ts", Mention.relativeTo("/p/.claude/worktrees/wt-1/a.ts", "/p/"))
        assertEquals(null, Mention.relativeTo("/q/a.ts", "/p"))
        assertEquals(null, Mention.relativeTo("/pp/a.ts", "/p"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.ui.*' --tests 'claudechat.ide.MentionTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Too many arguments for constructor ChatBridge", "Unresolved reference 'relativeTo'").

- [ ] **Step 3: Rewrite `ChatBridge`**

`src/main/kotlin/claudechat/ui/ChatBridge.kt`:
```kotlin
package claudechat.ui

import claudechat.cli.PermissionMode
import claudechat.cli.arr
import claudechat.cli.long
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.ide.FileLinker
import claudechat.session.Chat
import claudechat.session.ChatItem
import claudechat.session.PermissionDecision
import claudechat.session.SessionState
import claudechat.session.SessionView
import claudechat.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Connects one chat page to one [Chat]: web-view messages in, chat updates out. */
class ChatBridge(
    private val project: Project,
    private val chat: Chat,
    private val onNewChat: () -> Unit,
    private val post: (String) -> Unit,
) : SessionView {
    private val linker get() = FileLinker.getInstance(project)
    private val session get() = chat.session

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> { post(Theme.message()); chat.attach(this) }
            "send" -> msg.str("text")?.takeIf { it.isNotBlank() }?.let(chat::send)
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                session.respond(requestId, decision)
            }
            "setMode" -> PermissionMode.fromCli(msg.str("mode"))?.let(session::setPermissionMode)
            "interrupt" -> session.interrupt()
            "newChat" -> onNewChat()
            "restart" -> session.restart()
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "openDiff" -> msg.str("toolUseId")?.let { id -> session.diffFor(id)?.let(linker::showDiff) }
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
        }
    }

    override fun reset(items: List<ChatItem>) = post(buildJsonObject {
        put("type", "reset")
        put("items", JsonArray(items.map { it.toJson() }))
    }.toString())

    override fun item(item: ChatItem) = post(buildJsonObject {
        put("type", "item")
        put("item", item.toJson())
    }.toString())

    override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)
        put("sessionId", sessionId)
        put("mode", mode?.cli)
    }.toString())

    override fun insert(text: String) = post(buildJsonObject {
        put("type", "insert")
        put("text", text)
    }.toString())

    private fun resolvedMessage(reqId: Long, candidates: List<String>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for (candidate in candidates) {
                val resolved = linker.resolve(candidate) ?: continue
                put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
            }
        }
    }.toString()
}
```

- [ ] **Step 4: Rewrite `ChatPanel` (navigation guard copied unchanged)**

`src/main/kotlin/claudechat/ui/ChatPanel.kt`:
```kotlin
package claudechat.ui

import claudechat.session.Chat
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/** JCEF browser hosting one chat's page, wired to a [ChatBridge] bound to that chat. */
class ChatPanel(project: Project, private val chat: Chat, onNewChat: () -> Unit, parent: Disposable) : Disposable {
    val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val bridge = ChatBridge(project, chat, onNewChat, ::post)
    @Volatile private var pageUrl: String? = null
    @Volatile private var disposed = false

    init {
        Disposer.register(parent, this)

        query.addHandler { json ->
            ApplicationManager.getApplication().invokeLater({ if (!disposed) bridge.handle(json) }, project.disposed)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                // The first main-frame load is our own page; anything else never gets the bridge.
                val url = cefBrowser.url
                val page = pageUrl ?: url.also { pageUrl = it }
                if (url != page) return
                val js = "window.__send = function(m) { ${query.inject("m")} }; window.dispatchEvent(new Event('bridge-ready'));"
                cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            // Once the chat page has loaded, refuse every main-frame navigation (dropped files, stray links).
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
            ): Boolean = frame.isMain && pageUrl != null
        }, browser.cefBrowser)
        browser.loadHTML(WebAssets.page())
    }

    private fun post(json: String) {
        if (disposed) return
        browser.cefBrowser.executeJavaScript("window.chat && window.chat.receive($json);", "", 0)
    }

    override fun dispose() {
        disposed = true
        chat.detach(bridge)
        Disposer.dispose(query)
        Disposer.dispose(browser)
    }
}
```

- [ ] **Step 5: Add tabs, the `+` action and the factory**

`src/main/kotlin/claudechat/ui/ChatTabs.kt`:
```kotlin
package claudechat.ui

import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/** One closeable tool-window tab per chat. Closing a tab closes its chat (stops its process). EDT only. */
class ChatTabs(private val project: Project, private val toolWindow: ToolWindow) {
    private val chats get() = Chats.getInstance(project)

    fun open(spec: ChatSpec): Chat {
        val registry = chats // captured: the service may be unreachable while the project is closing
        val chat = registry.open(spec)
        val content = ContentFactory.getInstance().createContent(null, chat.title, false)
        content.isCloseable = true
        content.putUserData(CHAT, chat)
        Disposer.register(content) { registry.close(chat) }
        val panel = ChatPanel(project, chat, onNewChat = { open(chats.mainSpec()) }, parent = content)
        content.component = panel.browser.component
        content.preferredFocusableComponent = panel.browser.component
        chat.onTitleChanged = { content.displayName = chat.title } // Chat.send runs on the EDT
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)
        return chat
    }

    fun selectedChat(): Chat? = toolWindow.contentManager.selectedContent?.getUserData(CHAT)

    companion object {
        val CHAT: Key<Chat> = Key.create("claudechat.chat")

        fun find(project: Project): ChatTabs? =
            ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID)?.let { ChatTabs(project, it) }
    }
}
```

`src/main/kotlin/claudechat/ui/NewChatAction.kt`:
```kotlin
package claudechat.ui

import claudechat.session.Chats
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The `+` in the Claude Chat title bar. */
class NewChatAction : DumbAwareAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ChatTabs.find(project)?.open(Chats.getInstance(project).mainSpec())
    }
}
```

`src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt`:
```kotlin
package claudechat.ui

import claudechat.session.Chats
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            val label = JBLabel("Claude Chat needs JCEF, which is not available in this IDE runtime.")
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(label, null, false))
            return
        }
        toolWindow.setTitleActions(listOf(NewChatAction()))
        val tabs = ChatTabs(project, toolWindow)
        tabs.open(Chats.getInstance(project).mainSpec())
        // Showing the tool window after every tab was closed starts a fresh main chat.
        project.messageBus.connect(toolWindow.disposable).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == ID && shown.contentManager.contentCount == 0) tabs.open(Chats.getInstance(project).mainSpec())
            }
        })
    }

    companion object {
        const val ID = "Claude Chat"
    }
}
```

In `src/main/resources/META-INF/plugin.xml` replace lines 16-17:
```xml
        <toolWindow id="Claude Chat" anchor="right" icon="AllIcons.Toolwindows.ToolWindowMessages"
                    canCloseContents="true" factoryClass="claudechat.ui.ChatToolWindowFactory"/>
```

- [ ] **Step 6: Point "Add to Claude Chat" at the selected tab**

`src/main/kotlin/claudechat/ide/AddToChatAction.kt`:
```kotlin
package claudechat.ide

import claudechat.session.Chats
import claudechat.ui.ChatTabs
import claudechat.ui.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefApp

object Mention {
    fun format(path: String, startLine: Int?, endLine: Int?): String = when {
        startLine == null -> "@$path"
        endLine == null || endLine == startLine -> "@$path#L$startLine"
        else -> "@$path#L$startLine-$endLine"
    }

    /** [path] relative to [dir], or null when it is not inside it. */
    fun relativeTo(path: String, dir: String): String? {
        val prefix = dir.trimEnd('/') + "/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else null
    }
}

/** Inserts an @-reference to the current file (and selected lines) into the selected chat tab's input. */
class AddToChatAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val rootRelative = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
            ?.let { VfsUtilCore.getRelativePath(file, it) }

        val selection = editor.selectionModel
        val lines = if (selection.hasSelection()) {
            val doc = editor.document
            val start = selection.selectionStart
            val end = if (selection.selectionEnd > start) selection.selectionEnd - 1 else start
            doc.getLineNumber(start) + 1 to doc.getLineNumber(end) + 1
        } else {
            null
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID) ?: return
        toolWindow.activate {
            val tabs = ChatTabs(project, toolWindow)
            val chat = tabs.selectedChat()
                ?: if (JBCefApp.isSupported()) tabs.open(Chats.getInstance(project).mainSpec()) else return@activate
            // Claude resolves paths against its own folder, which for a worktree chat is not the content root.
            val path = Mention.relativeTo(file.path, chat.cwd) ?: rootRelative ?: file.path
            chat.insert(Mention.format(path, lines?.first, lines?.second))
        }
    }
}
```

- [ ] **Step 7: Remove the History dropdown from the page**

In `src/main/resources/webview/index.html` delete line 11:
```html
  <select id="sessions" title="Resume a past session"><option value="">History…</option></select>
```

In `src/main/resources/webview/chat.js`:

Delete line 8:
```js
  const sessionsSel = document.getElementById('sessions');
```

Delete line 48:
```js
        case 'sessions': fillSessions(msg.sessions); break;
```

Delete the `fillSessions` function (lines 169-176):
```js
  function fillSessions(sessions) {
    sessionsSel.textContent = '';
    sessionsSel.append(h('option', { value: '' }, sessions.length ? 'History…' : 'No past sessions'));
    for (const s of sessions) {
      const when = new Date(s.modified).toLocaleString();
      sessionsSel.append(h('option', { value: s.id, title: when }, s.title));
    }
  }
```

Delete the dropdown listeners (lines 211-217):
```js
  sessionsSel.addEventListener('mousedown', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('focus', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('change', () => {
    const id = sessionsSel.value;
    sessionsSel.value = '';
    if (id) send({ type: 'resume', sessionId: id });
  });
```

Leave everything else in `chat.js` and `index.html` (CSP, `SANITIZE`, `trusted`, click handling) untouched.

- [ ] **Step 8: Delete `SessionManager` and its test**

```bash
git rm src/main/kotlin/claudechat/session/SessionManager.kt src/test/kotlin/claudechat/session/SessionManagerTest.kt
```

- [ ] **Step 9: Run the full suite and build**

Run: `./gw test buildPlugin`
Expected: BUILD SUCCESSFUL, all tests PASS (no remaining reference to `SessionManager`: `grep -rn SessionManager src` prints nothing).

Tabs, the `+` action, tab closing, auto-created main tab and "Add to Claude Chat" are UI wiring with no unit tests; they are verified manually in Task 10.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/claudechat/ui src/main/kotlin/claudechat/ide/AddToChatAction.kt \
  src/main/resources/META-INF/plugin.xml src/main/resources/webview/index.html src/main/resources/webview/chat.js \
  src/test/kotlin/claudechat/ui/ChatBridgeTest.kt src/test/kotlin/claudechat/ui/WebAssetsTest.kt \
  src/test/kotlin/claudechat/ide/MentionTest.kt
git commit -m "Show one tool-window tab per chat"
```

---

### Task 6: New-chat popup (project, new worktree, existing worktrees, resume)

**Files:**
- Create: `src/main/kotlin/claudechat/ui/NewChatChoices.kt`
- Create: `src/main/kotlin/claudechat/ui/NewChatPopup.kt`
- Modify: `src/main/kotlin/claudechat/session/Chats.kt` (add `worktrees`)
- Modify: `src/main/kotlin/claudechat/ui/ChatTabs.kt` (`newChat`, page button)
- Modify: `src/main/kotlin/claudechat/ui/NewChatAction.kt` (show popup)
- Test: `src/test/kotlin/claudechat/ui/NewChatChoicesTest.kt`

**Interfaces:**
- Consumes: `Worktree`, `GitWorktrees.list/knownFolders/existingNames`, `WorktreeNames.error/suggest` (Task 2); `SessionStore.listAll`, `SessionSummary` (Task 3); `ChatSpec.*`, `ChatNames.folderLabel`, `Chats` (Task 4); `ChatTabs.open` (Task 5).
- Produces:
  - `var Chats.worktrees: GitWorktrees` (tests may replace it).
  - `sealed interface NewChatChoice { val text: String }` with `Open(text, spec: ChatSpec)`, `NewWorktree`, `Resume`.
  - `object NewChatChoices { fun build(projectDir: String, worktrees: List<Worktree>?): List<NewChatChoice>; fun resumeText(s: SessionSummary, projectDir: String, time: (Long) -> String): String }`.
  - `object NewChatPopup { fun show(project: Project, tabs: ChatTabs, place: (JBPopup) -> Unit) }`.
  - `ChatTabs.newChat(place: (JBPopup) -> Unit)`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/ui/NewChatChoicesTest.kt`:
```kotlin
package claudechat.ui

import claudechat.git.Worktree
import claudechat.session.ChatSpec
import claudechat.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class NewChatChoicesTest {
    private val main = Worktree("/p", "main", isMain = true)
    private val wt = Worktree("/p/.claude/worktrees/wt-1", "worktree-wt-1", isMain = false)
    private val detached = Worktree("/elsewhere/co", null, isMain = false)

    @Test fun withoutGitOffersProjectAndResumeOnly() = assertEquals(
        listOf(NewChatChoice.Open("In this project", ChatSpec.inProject("/p")), NewChatChoice.Resume),
        NewChatChoices.build("/p", null))

    @Test fun withGitOffersNewWorktreeAndEachLinkedWorktree() = assertEquals(
        listOf(
            NewChatChoice.Open("In this project", ChatSpec.inProject("/p")),
            NewChatChoice.NewWorktree,
            NewChatChoice.Open("In worktree wt-1 (worktree-wt-1)", ChatSpec("wt-1", "/p/.claude/worktrees/wt-1")),
            NewChatChoice.Open("In worktree co", ChatSpec("co", "/elsewhere/co")),
            NewChatChoice.Resume,
        ),
        NewChatChoices.build("/p", listOf(main, wt, detached)))

    @Test fun worktreeThatIsTheProjectItselfIsNotListed() = assertEquals(
        listOf(NewChatChoice.Open("In this project", ChatSpec.inProject("/p/wt")), NewChatChoice.NewWorktree, NewChatChoice.Resume),
        NewChatChoices.build("/p/wt", listOf(Worktree("/p", "main", true), Worktree("/p/wt", "feature", false))))

    @Test fun resumeTextShowsTitleFolderAndTime() {
        assertEquals(
            "fix bug · wt-1 · T5",
            NewChatChoices.resumeText(SessionSummary("s", "fix bug", 5, "/p/.claude/worktrees/wt-1"), "/p") { "T$it" })
        assertEquals("hello · main · T7", NewChatChoices.resumeText(SessionSummary("s", "hello", 7, "/p"), "/p") { "T$it" })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ui.NewChatChoicesTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Unresolved reference 'NewChatChoice'").

- [ ] **Step 3: Implement the choice model**

`src/main/kotlin/claudechat/ui/NewChatChoices.kt`:
```kotlin
package claudechat.ui

import claudechat.git.Worktree
import claudechat.session.ChatNames
import claudechat.session.ChatSpec
import claudechat.session.SessionSummary

/** One row of the New-chat popup. */
sealed interface NewChatChoice {
    val text: String

    data class Open(override val text: String, val spec: ChatSpec) : NewChatChoice

    data object NewWorktree : NewChatChoice {
        override val text = "In a new worktree…"
    }

    data object Resume : NewChatChoice {
        override val text = "Resume a past session…"
    }
}

object NewChatChoices {
    /** [worktrees] is `git worktree list` (null without git, which hides the worktree rows). */
    fun build(projectDir: String, worktrees: List<Worktree>?): List<NewChatChoice> = buildList {
        add(NewChatChoice.Open("In this project", ChatSpec.inProject(projectDir)))
        if (worktrees != null) {
            add(NewChatChoice.NewWorktree)
            for (wt in worktrees.filterNot { it.isMain || it.path.trimEnd('/') == projectDir.trimEnd('/') }) {
                val branch = wt.branch?.let { " ($it)" }.orEmpty()
                add(NewChatChoice.Open("In worktree ${wt.name}$branch", ChatSpec.existingWorktree(wt.path)))
            }
        }
        add(NewChatChoice.Resume)
    }

    fun resumeText(s: SessionSummary, projectDir: String, time: (Long) -> String): String =
        "${s.title} · ${ChatNames.folderLabel(s.folder, projectDir)} · ${time(s.modifiedMillis)}"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.ui.NewChatChoicesTest'`
Expected: BUILD SUCCESSFUL, 4 tests PASS.

- [ ] **Step 5: Add the popups and wire them in**

`src/main/kotlin/claudechat/ui/NewChatPopup.kt`:
```kotlin
package claudechat.ui

import claudechat.git.Worktree
import claudechat.git.WorktreeNames
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.SessionSummary
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.text.DateFormatUtil

/** The New-chat popups. Their contents are computed off the EDT (git, transcript files); [place] shows them. */
object NewChatPopup {
    fun show(project: Project, tabs: ChatTabs, place: (JBPopup) -> Unit) {
        val chats = Chats.getInstance(project)
        background(project, { chats.worktrees.list() }) { worktrees ->
            val choices = NewChatChoices.build(chats.projectDir, worktrees)
            place(listPopup(project, "New Chat", choices, NewChatChoice::text) { choice ->
                chosen(project, tabs, choice, worktrees, place)
            })
        }
    }

    private fun chosen(project: Project, tabs: ChatTabs, choice: NewChatChoice, worktrees: List<Worktree>?, place: (JBPopup) -> Unit) {
        val chats = Chats.getInstance(project)
        when (choice) {
            is NewChatChoice.Open -> tabs.open(choice.spec)
            NewChatChoice.NewWorktree ->
                askWorktreeName(project, chats.worktrees.existingNames(worktrees))
                    ?.let { tabs.open(ChatSpec.newWorktree(chats.projectDir, it)) }
            NewChatChoice.Resume -> showResume(project, tabs, chats.worktrees.knownFolders(worktrees), place)
        }
    }

    private fun showResume(project: Project, tabs: ChatTabs, folders: List<String>, place: (JBPopup) -> Unit) {
        val chats = Chats.getInstance(project)
        background(project, { chats.store.listAll(folders) }) { sessions ->
            if (sessions.isEmpty()) {
                place(JBPopupFactory.getInstance().createMessage("No past sessions in this project or its worktrees."))
            } else {
                val text = { s: SessionSummary ->
                    NewChatChoices.resumeText(s, chats.projectDir, DateFormatUtil::formatPrettyDateTime)
                }
                place(listPopup(project, "Resume a Past Session", sessions, text) { s ->
                    tabs.open(ChatSpec.resume(chats.projectDir, s))
                })
            }
        }
    }

    private fun askWorktreeName(project: Project, existing: Set<String>): String? = Messages.showInputDialog(
        project,
        "Name for the new worktree (folder .claude/worktrees/<name>, branch worktree-<name>):",
        "New Worktree",
        null,
        WorktreeNames.suggest(existing),
        object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = WorktreeNames.error(inputString.trim(), existing)
            override fun checkInput(inputString: String): Boolean = getErrorText(inputString) == null
            override fun canClose(inputString: String): Boolean = checkInput(inputString)
        },
    )?.trim()

    /** Runs [compute] on a pooled thread, then [show] on the EDT unless the project has closed. */
    private fun <T> background(project: Project, compute: () -> T, show: (T) -> Unit) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = compute()
            app.invokeLater({ show(result) }, project.disposed)
        }
    }

    /** A speed-searchable list; [onChosen] runs on the EDT after the popup has closed. */
    private fun <T> listPopup(project: Project, title: String, items: List<T>, text: (T) -> String, onChosen: (T) -> Unit): JBPopup =
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create<T>("") { text(it) })
            .setNamerForFiltering { text(it) }
            .setItemChosenCallback { item -> ApplicationManager.getApplication().invokeLater({ onChosen(item) }, project.disposed) }
            .createPopup()
}
```

In `src/main/kotlin/claudechat/session/Chats.kt` add the import:
```kotlin
import claudechat.git.GitWorktrees
```
and, directly below `var store: SessionStore = SessionStore.default()`:
```kotlin
    var worktrees: GitWorktrees = GitWorktrees(projectDir)
```

In `src/main/kotlin/claudechat/ui/ChatTabs.kt` add the import:
```kotlin
import com.intellij.openapi.ui.popup.JBPopup
```
replace the line
```kotlin
        val panel = ChatPanel(project, chat, onNewChat = { open(chats.mainSpec()) }, parent = content)
```
with
```kotlin
        lateinit var panel: ChatPanel
        panel = ChatPanel(project, chat, onNewChat = { newChat { it.showInCenterOf(panel.browser.component) } }, parent = content)
```
and add below `fun selectedChat()`:
```kotlin

    /** Shows the New-chat popup; [place] positions it. */
    fun newChat(place: (JBPopup) -> Unit) = NewChatPopup.show(project, this, place)
```

Replace `src/main/kotlin/claudechat/ui/NewChatAction.kt` with:
```kotlin
package claudechat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** The `+` in the Claude Chat title bar: opens the New-chat popup under the button. */
class NewChatAction : DumbAwareAction("New Chat", "Start a new Claude chat", AllIcons.General.Add) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tabs = ChatTabs.find(project) ?: return
        val anchor = e.inputEvent?.component
        tabs.newChat { popup ->
            if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
        }
    }
}
```

- [ ] **Step 6: Run the full suite and build**

Run: `./gw test buildPlugin`
Expected: BUILD SUCCESSFUL, all tests PASS.

The popups, the worktree-name dialog and their placement are verified manually in Task 10; their content comes from `NewChatChoices`, `WorktreeNames` and `GitWorktrees`, which are unit-tested.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/claudechat/ui/NewChatChoices.kt src/main/kotlin/claudechat/ui/NewChatPopup.kt \
  src/main/kotlin/claudechat/ui/ChatTabs.kt src/main/kotlin/claudechat/ui/NewChatAction.kt \
  src/main/kotlin/claudechat/session/Chats.kt src/test/kotlin/claudechat/ui/NewChatChoicesTest.kt
git commit -m "Add the New-chat popup with worktree and resume options"
```

---

### Task 7: Per-chat edit notes

**Files:**
- Modify: `src/main/kotlin/claudechat/ide/EditTracker.kt` (rewrite)
- Modify: `src/main/kotlin/claudechat/session/Chats.kt` (`open`, `close`)
- Test: `src/test/kotlin/claudechat/ide/EditTrackerTest.kt` (rewrite), `src/test/kotlin/claudechat/ide/EditNotesTest.kt` (create), `src/test/kotlin/claudechat/session/ChatsTest.kt`

**Interfaces:**
- Consumes: `Chat.id`, `Chat.cwd`, `Chats.open/close` (Task 4).
- Produces:
  - `EditTracker.record(file: VirtualFile)` records `file.path` (absolute) for every saved document; `fun register(key: Any)` (the key is told only about saves from now on); `fun forget(key: Any)`; `fun consumeNote(key: Any, cwd: String): String?` (EDT; saves all documents, returns the note for files saved since `key`'s last consume that are under `cwd` but not under `cwd/.claude/worktrees/`, advances `key` only). `clear()` and the no-argument `consumeNote()` are removed.
  - `object EditNotes { fun underFolder(cwd: String, paths: List<String>): List<String>; fun note(files: List<String>): String? }`.
  - `Chats.open` registers `chat.id`; `Chats.close` forgets it.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/ide/EditNotesTest.kt`:
```kotlin
package claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditNotesTest {
    @Test fun keepsFilesUnderFolderAsRelativePaths() = assertEquals(
        listOf("a.txt", "src/b.txt"),
        EditNotes.underFolder("/p", listOf("/p/a.txt", "/p/src/b.txt", "/q/c.txt", "/pp/d.txt")))

    @Test fun excludesClaudeWorktreesInsideTheFolder() = assertEquals(
        listOf("x.txt"),
        EditNotes.underFolder("/p/", listOf("/p/x.txt", "/p/.claude/worktrees/wt1/y.txt")))

    @Test fun worktreeFolderSeesOnlyItsOwnFiles() = assertEquals(
        listOf("y.txt"),
        EditNotes.underFolder("/p/.claude/worktrees/wt1", listOf("/p/x.txt", "/p/.claude/worktrees/wt1/y.txt", "/p/.claude/worktrees/wt2/z.txt")))

    @Test fun noteText() {
        assertNull(EditNotes.note(emptyList()))
        assertEquals(
            "[IDE] Since your last turn the user edited these files: a.txt, b/c.txt.\nRe-read them before relying on earlier contents.",
            EditNotes.note(listOf("a.txt", "b/c.txt")))
    }
}
```

Replace `src/test/kotlin/claudechat/ide/EditTrackerTest.kt` with:
```kotlin
package claudechat.ide

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditTrackerTest : BasePlatformTestCase() {
    private val tracker get() = EditTracker.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val worktree get() = "$root/.claude/worktrees/wt1"

    private fun note(vararg files: String) =
        "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\nRe-read them before relying on earlier contents."

    /** Opens [path] (relative to the content root) and types into it without saving. */
    private fun edit(path: String) {
        myFixture.openFileInEditor(myFixture.addFileToProject(path, "x").virtualFile)
        myFixture.type("y")
    }

    override fun setUp() {
        super.setUp()
        // The light project is shared between tests: save leftovers first, then start every key clean.
        FileDocumentManager.getInstance().saveAllDocuments()
        tracker.register("main")
        tracker.register("wt")
    }

    fun testUnsavedEditIsReportedOnce() {
        edit("a.txt")
        assertEquals(note("a.txt"), tracker.consumeNote("main", root))
        assertNull(tracker.consumeNote("main", root))
    }

    fun testEveryChatWhoseFolderHoldsTheFileIsToldOnce() {
        tracker.register("other")
        edit("b.txt")
        assertEquals(note("b.txt"), tracker.consumeNote("main", root))
        assertEquals(note("b.txt"), tracker.consumeNote("other", root))
        assertNull(tracker.consumeNote("other", root))
    }

    fun testWorktreeFilesGoToTheWorktreeChatOnly() {
        edit(".claude/worktrees/wt1/src/c.txt")
        assertNull(tracker.consumeNote("main", root))
        assertEquals(note("src/c.txt"), tracker.consumeNote("wt", worktree))
    }

    fun testFilesOutsideTheFolderAreNotReported() {
        edit("d.txt")
        assertNull(tracker.consumeNote("wt", worktree))
        assertEquals(note("d.txt"), tracker.consumeNote("main", root))
    }

    fun testRegisteringIgnoresEarlierSaves() {
        edit("e.txt")
        FileDocumentManager.getInstance().saveAllDocuments()
        tracker.register("late")
        assertNull(tracker.consumeNote("late", root))
        assertEquals(note("e.txt"), tracker.consumeNote("main", root))
    }

    fun testExternalChangeIsNotReported() {
        val file = myFixture.addFileToProject("f.txt", "a").virtualFile
        WriteAction.run<Exception> { file.setBinaryContent("b".toByteArray()) }
        assertNull(tracker.consumeNote("main", root))
    }
}
```

In `src/test/kotlin/claudechat/session/ChatsTest.kt`:

Replace the import `import claudechat.ide.EditTracker` with:
```kotlin
import com.intellij.openapi.fileEditor.FileDocumentManager
```

Replace the setUp line
```kotlin
        EditTracker.getInstance(project).consumeNote() // drop saves recorded by earlier tests
```
with
```kotlin
        FileDocumentManager.getInstance().saveAllDocuments() // chats opened below only hear about later saves
```

Add before the final closing `}`:
```kotlin

    fun testEditNotesFollowEachChatsFolder() {
        val main = chats.open(ChatSpec.inProject(root))
        val wt = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        myFixture.openFileInEditor(myFixture.addFileToProject(".claude/worktrees/wt1/b.txt", "x").virtualFile)
        myFixture.type("y")
        main.send("first")
        wt.send("second")
        assertEquals(listOf("first"), connections[0].userMessages())
        assertTrue(connections[1].userMessages().single().startsWith("[IDE] Since your last turn the user edited these files: b.txt."))
    }

    fun testChatOpenedLaterIsNotToldAboutEarlierSaves() {
        myFixture.configureByText("early.txt", "x")
        myFixture.type("y")
        FileDocumentManager.getInstance().saveAllDocuments()
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hi")
        assertEquals(listOf("hi"), connections.single().userMessages())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.ide.EditNotesTest' --tests 'claudechat.ide.EditTrackerTest' --tests 'claudechat.session.ChatsTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Unresolved reference 'EditNotes'", "Unresolved reference 'register'").

- [ ] **Step 3: Implement**

Replace `src/main/kotlin/claudechat/ide/EditTracker.kt` with:
```kotlin
package claudechat.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Records files the user saved from the IDE, by absolute path. Claude's own writes are disk changes that the
 * IDE reloads (never saves), so they are not recorded. Each chat (a key) has its own cursor: it is told about
 * a file once, and only when the file is in its folder.
 */
@Service(Service.Level.PROJECT)
class EditTracker(private val project: Project) : Disposable {
    private val lock = Any()
    /** Absolute path -> sequence number of its latest save. */
    private val saved = LinkedHashMap<String, Long>()
    private var seq = 0L
    /** Chat key -> sequence number it has been told up to. */
    private val cursors = HashMap<Any, Long>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    FileDocumentManager.getInstance().getFile(document)?.let(::record)
                }
            })
    }

    fun record(file: VirtualFile) {
        if (project.isDisposed) return
        synchronized(lock) {
            saved.remove(file.path)
            saved[file.path] = ++seq
        }
    }

    /** From now on [key] hears about saves made after this call only. */
    fun register(key: Any) {
        synchronized(lock) { cursors[key] = seq }
    }

    fun forget(key: Any) {
        synchronized(lock) { cursors.remove(key) }
    }

    /**
     * Saves all documents (recording unsaved user edits), then returns the note for [key]'s next message:
     * files saved since its last call that lie under [cwd] (not under `cwd/.claude/worktrees/`), or null.
     * Advances only [key]. EDT only.
     */
    fun consumeNote(key: Any, cwd: String): String? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val files = synchronized(lock) {
            val since = cursors[key] ?: 0L
            cursors[key] = seq
            saved.filterValues { it > since }.keys.toList()
        }
        return EditNotes.note(EditNotes.underFolder(cwd, files))
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): EditTracker = project.service()
    }
}

/** Which saved files a chat is told about, and the note text. */
object EditNotes {
    /** [paths] under [cwd] (excluding worktrees claude keeps in `cwd/.claude/worktrees`), relative to [cwd]. */
    fun underFolder(cwd: String, paths: List<String>): List<String> {
        val dir = cwd.trimEnd('/') + "/"
        val worktrees = dir + ".claude/worktrees/"
        return paths.filter { it.startsWith(dir) && !it.startsWith(worktrees) }.map { it.removePrefix(dir) }
    }

    fun note(files: List<String>): String? =
        if (files.isEmpty()) null
        else "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\n" +
            "Re-read them before relying on earlier contents."
}
```

In `src/main/kotlin/claudechat/session/Chats.kt` replace the body of `open` with:
```kotlin
    fun open(spec: ChatSpec): Chat {
        val history = spec.resumeId?.let { store.transcript(spec.cwd, it) }.orEmpty()
        val tracker = EditTracker.getInstance(project)
        val chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { c -> tracker.consumeNote(c.id, c.cwd) },
            log = { LOG.info(it) },
        )
        tracker.register(chat.id)
        chats += chat
        chat.start()
        return chat
    }
```
and `close` with:
```kotlin
    fun close(chat: Chat) {
        if (chats.remove(chat)) {
            chat.dispose()
            EditTracker.getInstance(project).forget(chat.id)
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.ide.EditNotesTest' --tests 'claudechat.ide.EditTrackerTest' --tests 'claudechat.session.ChatsTest'`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gw test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/claudechat/ide/EditTracker.kt src/main/kotlin/claudechat/session/Chats.kt \
  src/test/kotlin/claudechat/ide/EditNotesTest.kt src/test/kotlin/claudechat/ide/EditTrackerTest.kt \
  src/test/kotlin/claudechat/session/ChatsTest.kt
git commit -m "Route edit notes to each chat whose folder holds the file"
```

---

### Task 8: File links resolve against the chat's folder

**Files:**
- Modify: `src/main/kotlin/claudechat/ide/FileLinker.kt:24-61`
- Modify: `src/main/kotlin/claudechat/ui/ChatBridge.kt` (`resolvedMessage`)
- Test: `src/test/kotlin/claudechat/ide/FileLinkerTest.kt`, `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`

**Interfaces:**
- Consumes: `Chat.cwd` (Task 4), `ChatBridge` (Task 5).
- Produces: `FileLinker.resolve(candidate: String, cwd: String? = null): ResolvedPath?` — relative paths are tried against `cwd` first, then the content roots; absolute paths as given; a file is accepted if it is project content or under `cwd`. `FileLinker.open(target, line)` is unchanged (absolute paths and URLs open as-is).

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/claudechat/ide/FileLinkerTest.kt` add these imports:
```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.io.File
import java.nio.file.Files
```
add below `private val linker ...`:
```kotlin
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
```
and add before the final closing `}`:
```kotlin

    fun testRelativePathResolvesAgainstChatFolderFirst() {
        myFixture.addFileToProject("lib/x.txt", "project copy")
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/x.txt", "worktree copy").virtualFile
        assertEquals(ResolvedPath(inWorktree.url, 3), linker.resolve("lib/x.txt:3", "$root/.claude/worktrees/wt1"))
    }

    fun testFallsBackToContentRootsWhenNotInChatFolder() {
        val inProject = myFixture.addFileToProject("lib/y.txt", "x").virtualFile
        assertEquals(ResolvedPath(inProject.url, null), linker.resolve("lib/y.txt", "$root/.claude/worktrees/wt1"))
    }

    fun testFileOutsideProjectResolvesOnlyUnderChatFolder() {
        val dir = Files.createTempDirectory("wt-outside").toFile().canonicalFile
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        try {
            val notes = File(dir, "notes.txt").apply { writeText("x") }
            val url = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(notes)!!.url
            assertEquals(ResolvedPath(url, null), linker.resolve("notes.txt", dir.path))
            assertEquals(ResolvedPath(url, 2), linker.resolve("${notes.path}:2", dir.path))
            assertNull(linker.resolve("notes.txt", null))
            assertNull(linker.resolve(notes.path, null))
            assertNull(linker.resolve(notes.path, "/elsewhere"))
        } finally {
            dir.deleteRecursively()
        }
    }
```

In `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt` add before the final closing `}`:
```kotlin

    fun testResolvePathsUsesTheChatsFolder() {
        val inWorktree = myFixture.addFileToProject(".claude/worktrees/wt1/lib/z.txt", "a").virtualFile
        val wtChat = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wtPosted = mutableListOf<JsonObject>()
        val wtBridge = ChatBridge(project, wtChat, {}) { wtPosted += parseJsonObject(it)!! }
        wtBridge.handle("""{"type":"resolvePaths","reqId":1,"candidates":["lib/z.txt"]}""")
        assertEquals(inWorktree.url, wtPosted.single().obj("paths")!!.obj("lib/z.txt")!!.str("target"))
        bridge.handle("""{"type":"resolvePaths","reqId":2,"candidates":["lib/z.txt"]}""")
        assertNull(posted.single().obj("paths")!!["lib/z.txt"])
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.ide.FileLinkerTest' --tests 'claudechat.ui.ChatBridgeTest'`
Expected: BUILD FAILED in `compileTestKotlin` ("Too many arguments for public final fun resolve(candidate: String)").

- [ ] **Step 3: Implement**

In `src/main/kotlin/claudechat/ide/FileLinker.kt` replace lines 24-61 (`resolve`, `findFile`, `lookup`) with:
```kotlin
    /**
     * Resolves a path Claude mentioned. Relative paths are tried against [cwd] (the chat's folder) first, then
     * the project content roots. A file counts if it is project content or lies under [cwd].
     */
    fun resolve(candidate: String, cwd: String? = null): ResolvedPath? {
        val match = pattern.matchEntire(candidate.trim()) ?: return null
        val raw = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val file = findFile(raw, cwd) ?: return null
        return ResolvedPath(file.url, line)
    }

    private fun findFile(raw: String, cwd: String?): VirtualFile? {
        val cached = ReadAction.compute<VirtualFile?, RuntimeException> { lookup(raw, cwd) }
        if (cached != null) return cached
        // Claude may have just created the file; the VFS may not know yet. Refresh outside the read action.
        val relative = raw.removePrefix("./")
        val local = LocalFileSystem.getInstance()
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.filter { it.fileSystem === local }
        }
        val paths = buildList {
            if (raw.startsWith("/")) {
                add(raw)
            } else {
                if (cwd != null) add("${cwd.trimEnd('/')}/$relative")
                roots.forEach { add("${it.path}/$relative") }
            }
        }
        val refreshed = paths.mapNotNull { local.refreshAndFindFileByPath(it) }
        return ReadAction.compute<VirtualFile?, RuntimeException> { refreshed.firstOrNull { accepts(it, cwd) } }
    }

    private fun lookup(raw: String, cwd: String?): VirtualFile? {
        val relative = raw.removePrefix("./")
        val candidates = buildList {
            if (raw.startsWith("/")) {
                LocalFileSystem.getInstance().findFileByPath(raw)?.let(::add)
            } else {
                folder(cwd)?.findFileByRelativePath(relative)?.let(::add)
                ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                    root.findFileByRelativePath(relative)?.let(::add)
                }
            }
        }
        return candidates.firstOrNull { accepts(it, cwd) }
    }

    /** The chat folder: on disk, or inside a content root that lives on another file system (tests use an in-memory one). */
    private fun folder(cwd: String?): VirtualFile? {
        if (cwd == null) return null
        LocalFileSystem.getInstance().findFileByPath(cwd)?.let { return it }
        val dir = cwd.trimEnd('/')
        return ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root ->
            when {
                dir == root.path -> root
                dir.startsWith(root.path + "/") -> root.findFileByRelativePath(dir.removePrefix(root.path + "/"))
                else -> null
            }
        }
    }

    private fun accepts(file: VirtualFile, cwd: String?): Boolean =
        !file.isDirectory && (ProjectFileIndex.getInstance(project).isInContent(file) || isUnder(file.path, cwd))

    private fun isUnder(path: String, dir: String?): Boolean = dir != null && path.startsWith(dir.trimEnd('/') + "/")
```

In `src/main/kotlin/claudechat/ui/ChatBridge.kt`, inside `resolvedMessage`, replace
```kotlin
                val resolved = linker.resolve(candidate) ?: continue
```
with
```kotlin
                val resolved = linker.resolve(candidate, chat.cwd) ?: continue
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.ide.FileLinkerTest' --tests 'claudechat.ui.ChatBridgeTest'`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Run the full suite**

Run: `./gw test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/claudechat/ide/FileLinker.kt src/main/kotlin/claudechat/ui/ChatBridge.kt \
  src/test/kotlin/claudechat/ide/FileLinkerTest.kt src/test/kotlin/claudechat/ui/ChatBridgeTest.kt
git commit -m "Resolve file links against the chat's folder"
```

---

### Task 9: Prompt history and Up/Down recall

**Files:**
- Create: `src/main/kotlin/claudechat/session/PromptHistory.kt`
- Modify: `src/main/kotlin/claudechat/ui/ChatBridge.kt` (`ready`, `send`, `dispose`, `historyMessage`)
- Modify: `src/main/kotlin/claudechat/ui/ChatPanel.kt` (`dispose`)
- Modify: `src/main/resources/webview/chat.js`
- Test: `src/test/kotlin/claudechat/session/PromptHistoryTest.kt`, `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`, `src/test/kotlin/claudechat/ui/WebAssetsTest.kt`

**Interfaces:**
- Consumes: `ChatBridge`, `ChatPanel` (Tasks 5 and 8).
- Produces:
  - `@Service(PROJECT) @State(name = "ClaudeChatPromptHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]) class PromptHistory : SimplePersistentStateComponent<PromptHistory.State>` with `State.entries: MutableList<String>`, `fun entries(): List<String>`, `fun add(text: String)`, `fun clear()`, `fun subscribe(listener: (List<String>) -> Unit): () -> Unit` (returns unsubscribe), companion `CAP = 100`, `fun append(entries: List<String>, text: String, cap: Int = CAP): List<String>`, `fun getInstance(project): PromptHistory`.
  - Bridge → page message `{"type":"history","entries":[...]}` posted on `ready` and whenever any tab sends. `ChatBridge.dispose()` unsubscribes.
  - Page: ArrowUp with the caret on the first line (no modifiers, no IME composition) recalls the previous entry; ArrowDown on the last line moves forward and finally restores the draft.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/session/PromptHistoryTest.kt`:
```kotlin
package claudechat.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptHistoryTest : BasePlatformTestCase() {
    private val history get() = PromptHistory.getInstance(project)

    override fun setUp() {
        super.setUp()
        history.clear() // project services persist between tests
    }

    fun testAppendKeepsOrderAndSkipsImmediateRepeat() = assertEquals(
        listOf("a", "b", "a"),
        listOf("a", "b", "b", "a").fold(emptyList<String>()) { acc, t -> PromptHistory.append(acc, t) })

    fun testAppendIgnoresBlank() = assertEquals(listOf("a"), PromptHistory.append(listOf("a"), "  "))

    fun testAppendCapsAtOneHundredDroppingOldest() {
        val next = PromptHistory.append((1..100).map { "m$it" }, "m101")
        assertEquals(100, next.size)
        assertEquals("m2", next.first())
        assertEquals("m101", next.last())
    }

    fun testAddStoresInStateAndNotifiesSubscribers() {
        val seen = mutableListOf<List<String>>()
        val unsubscribe = history.subscribe { seen += it }
        history.add("one")
        history.add("one")
        history.add("two")
        unsubscribe()
        history.add("three")
        assertEquals(listOf("one", "two", "three"), history.entries())
        assertEquals(listOf(listOf("one"), listOf("one", "two")), seen)
        assertEquals(listOf("one", "two", "three"), history.state.entries.toList())
    }

    fun testLoadStateRestoresEntries() {
        history.loadState(PromptHistory.State().apply { entries = mutableListOf("x", "y") })
        assertEquals(listOf("x", "y"), history.entries())
    }
}
```

In `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`:

Add the imports:
```kotlin
import claudechat.cli.arr
import claudechat.session.PromptHistory
import kotlinx.serialization.json.JsonPrimitive
```

In `setUp`, directly after `super.setUp()`, add:
```kotlin
        PromptHistory.getInstance(project).clear()
```

Replace `tearDown` with:
```kotlin
    override fun tearDown() {
        try { bridge.dispose(); chat.detach(bridge); chats.closeAll() } finally { super.tearDown() }
    }
```

Replace the `testReadySendsThemeResetAndState` test with:
```kotlin
    fun testReadySendsThemeHistoryResetAndState() {
        PromptHistory.getInstance(project).add("earlier")
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "history", "reset", "state"), types())
        assertEquals(listOf(JsonPrimitive("earlier")), posted[1].arr("entries")!!.toList())
        assertEquals("IDLE", posted.last().str("state"))
    }
```

Add before the final closing `}`:
```kotlin

    fun testSentTextIsRememberedAndBroadcastToOtherTabs() {
        val other = chats.open(ChatSpec.inProject(root))
        val otherPosted = mutableListOf<JsonObject>()
        val otherBridge = ChatBridge(project, other, {}) { otherPosted += parseJsonObject(it)!! }
        try {
            otherBridge.handle("""{"type":"ready"}""")
            bridge.handle("""{"type":"send","text":"hello"}""")
            assertEquals(listOf("hello"), PromptHistory.getInstance(project).entries())
            val last = otherPosted.last { it.str("type") == "history" }
            assertEquals(listOf(JsonPrimitive("hello")), last.arr("entries")!!.toList())
        } finally {
            otherBridge.dispose()
        }
    }

    fun testDisposedBridgeStopsReceivingHistory() {
        bridge.handle("""{"type":"ready"}""")
        bridge.dispose()
        val before = posted.size
        PromptHistory.getInstance(project).add("later")
        assertEquals(before, posted.size)
    }
```

In `src/test/kotlin/claudechat/ui/WebAssetsTest.kt` add before the final closing `}`:
```kotlin

    @Test fun pageHandlesHistoryRecall() {
        val page = WebAssets.page()
        assertTrue(page.contains("case 'history':"))
        assertTrue(page.contains("ArrowUp"))
        assertTrue(page.contains("ArrowDown"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.PromptHistoryTest' --tests 'claudechat.ui.*'`
Expected: BUILD FAILED in `compileTestKotlin` ("Unresolved reference 'PromptHistory'", "Unresolved reference 'dispose'").

- [ ] **Step 3: Implement the history service**

`src/main/kotlin/claudechat/session/PromptHistory.kt`:
```kotlin
package claudechat.session

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
```

- [ ] **Step 4: Wire it into the bridge and panel**

In `src/main/kotlin/claudechat/ui/ChatBridge.kt`:

Add the import:
```kotlin
import claudechat.session.PromptHistory
```

Below `private val session get() = chat.session` add:
```kotlin
    private val history get() = PromptHistory.getInstance(project)
    @Volatile private var unsubscribe: (() -> Unit)? = null
```

Replace the `"ready"` and `"send"` branches with:
```kotlin
            "ready" -> {
                post(Theme.message())
                post(historyMessage(history.entries()))
                if (unsubscribe == null) unsubscribe = history.subscribe { post(historyMessage(it)) }
                chat.attach(this)
            }
            "send" -> msg.str("text")?.takeIf { it.isNotBlank() }?.let { text ->
                history.add(text)
                chat.send(text)
            }
```

Add below `handle`:
```kotlin

    /** Stops history updates. Called when the page goes away. */
    fun dispose() {
        unsubscribe?.invoke()
        unsubscribe = null
    }
```

Add next to `resolvedMessage`:
```kotlin
    private fun historyMessage(entries: List<String>) = buildJsonObject {
        put("type", "history")
        put("entries", JsonArray(entries.map(::JsonPrimitive)))
    }.toString()
```

In `src/main/kotlin/claudechat/ui/ChatPanel.kt` replace `dispose` with:
```kotlin
    override fun dispose() {
        disposed = true
        bridge.dispose()
        chat.detach(bridge)
        Disposer.dispose(query)
        Disposer.dispose(browser)
    }
```

- [ ] **Step 5: Add recall to the page**

In `src/main/resources/webview/chat.js`:

Below `let reqSeq = 0;` add:
```js
  let history = [];  // sent messages, oldest first (shared by every tab, kept by the IDE)
  let recall = -1;   // index into history while recalling, -1 otherwise
  let draft = '';    // what the box held before recall started
```

In `window.chat.receive`, below `case 'theme': applyTheme(msg); break;` add:
```js
        case 'history': history = Array.isArray(msg.entries) ? msg.entries : []; break;
```

At the start of `insertText(text)` (first line of its body) add:
```js
    recall = -1;
```

Replace `submit()` with:
```js
  function submit() {
    const text = input.value.trim();
    if (!text || input.disabled) return;
    send({ type: 'send', text });
    input.value = '';
    recall = -1;
    draft = '';
  }
```

Replace the existing keydown listener
```js
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
  });
```
with:
```js
  const caretOnFirstLine = () => !input.value.slice(0, input.selectionStart).includes('\n');
  const caretOnLastLine = () => !input.value.slice(input.selectionEnd).includes('\n');

  function showRecalled(text) {
    input.value = text;
    input.setSelectionRange(text.length, text.length);
  }

  // ArrowUp: previous sent message (the first press saves the draft).
  function recallOlder() {
    if (recall === -1) {
      if (!history.length) return false;
      draft = input.value;
      recall = history.length;
    }
    recall = Math.min(recall, history.length);
    if (recall > 0) { recall--; showRecalled(history[recall]); }
    return true;
  }

  // ArrowDown: next sent message, then back to the draft.
  function recallNewer() {
    if (recall === -1) return false;
    recall++;
    if (recall >= history.length) { recall = -1; showRecalled(draft); } else showRecalled(history[recall]);
    return true;
  }

  input.addEventListener('keydown', (e) => {
    if (e.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); return; }
    if (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey) return;
    if (e.key === 'ArrowUp' && caretOnFirstLine() && recallOlder()) e.preventDefault();
    else if (e.key === 'ArrowDown' && caretOnLastLine() && recallNewer()) e.preventDefault();
  });
  // Typing ends recall: the edited text becomes the new draft.
  input.addEventListener('input', () => { recall = -1; });
```

Recalled text is only ever assigned to `input.value` (never parsed as HTML); nothing in the sanitising or click-trust code changes.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.PromptHistoryTest' --tests 'claudechat.ui.*'`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 7: Run the full suite and build**

Run: `./gw test buildPlugin`
Expected: BUILD SUCCESSFUL.

The keyboard behaviour in `chat.js` has no JS test harness; it is verified manually in Task 10.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/claudechat/session/PromptHistory.kt src/main/kotlin/claudechat/ui/ChatBridge.kt \
  src/main/kotlin/claudechat/ui/ChatPanel.kt src/main/resources/webview/chat.js \
  src/test/kotlin/claudechat/session/PromptHistoryTest.kt src/test/kotlin/claudechat/ui/ChatBridgeTest.kt \
  src/test/kotlin/claudechat/ui/WebAssetsTest.kt
git commit -m "Recall sent messages with ArrowUp and ArrowDown"
```

---

### Task 10: End-to-end check in a sandbox WebStorm

**Files:** none (fix-ups only if a check fails; each fix gets a regression test where the logic is testable outside the UI).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Prepare a scratch git project and launch the sandbox**

```bash
SCRATCH=$(mktemp -d)/v2-check && mkdir -p "$SCRATCH" && cd "$SCRATCH"
git init -q && printf 'hello\n' > notes.txt && git add . && git commit -qm init
echo "$SCRATCH"
cd /Users/atli/projects/plugin && ./gw runIde
```
In the sandbox WebStorm open the printed folder. Also prepare a second, non-git folder (`mkdir -p $(mktemp -d)/plain`) for check 12.

- [ ] **Step 2: Verify each requirement and note the result**

1. Open **Claude Chat**. One tab titled `main` exists, the status reads "Ready", and there is no History dropdown. Send "Say hi." — the reply streams in (this proves the bridge was injected on the chat page). The tab title becomes `main · Say hi.`.
2. Drag a file from Finder onto the chat page: the page does not navigate away and still works. Ask "Give me a markdown link to https://example.com" and click it: it opens in the external browser only.
3. Click the `+` in the tool-window title bar: a popup lists **In this project**, **In a new worktree…**, **Resume a past session…** (no existing worktrees yet). The page's **New chat** button shows the same popup centred on the page.
4. Choose **In a new worktree…**. The name prompt defaults to `wt-1`; typing `bad name` or `-x` shows an error and disables OK. Accept `wt-1`. A new tab `wt-1` opens. Send "Run pwd and tell me the folder." — Claude reports `<scratch>/.claude/worktrees/wt-1`. In a terminal, `git -C <scratch> worktree list` shows the worktree on branch `worktree-wt-1`.
5. Open the popup again: **In worktree wt-1 (worktree-wt-1)** is listed, and **In a new worktree…** now suggests `wt-2`; entering `wt-1` is rejected as existing. Cancel.
6. In the `wt-1` tab ask "Create lib/a.txt containing one line, then tell me its path in backticks." The backticked `lib/a.txt` link opens `<scratch>/.claude/worktrees/wt-1/lib/a.txt` (not a project-root file).
7. Edit notes: open `<scratch>/.claude/worktrees/wt-1/lib/a.txt` in the editor, type a character (don't save). In the `main` tab send "Did I edit any files? Answer yes or no only." — Claude says no. In the `wt-1` tab send the same — Claude says yes and names `lib/a.txt`. Edit `notes.txt` (project root) and ask both tabs again: only `main` is told, and each tab is told once.
8. Select two lines in `notes.txt` with the `main` tab selected and press Cmd+Shift+Option+K: `@notes.txt#Lx-y` appears in the `main` input. Select the `wt-1` tab, select lines in `.claude/worktrees/wt-1/lib/a.txt` and press the shortcut: `@lib/a.txt#L…` appears in the `wt-1` input.
9. Up/Down recall in the `wt-1` tab: type a draft `draft text`, press ArrowUp — the last sent message (from either tab) replaces it; ArrowUp again goes further back; ArrowDown walks forward and finally restores `draft text`. Type a two-line message (Shift+Enter): ArrowUp with the caret on the second line moves the caret instead of recalling. Send the same message twice: it appears once at the end of history. Switch to `main`: ArrowUp there recalls the message just sent from `wt-1`.
10. Close the `wt-1` tab with its ×. In a terminal, `ps aux | grep '[c]laude -p'` shows one fewer process. Restart the sandbox IDE (File > Exit, `./gw runIde` again, reopen the scratch folder): ArrowUp in the auto-created `main` tab recalls the earlier messages (history persisted).
11. **Resume a past session…**: a searchable list shows sessions from both the project and `wt-1`, newest first, each with title, `main`/`wt-1` and time; typing filters it. Pick the `wt-1` session: a tab titled `wt-1 · …` opens with the earlier messages; ask "What file did you create?" — it answers from that context. Close every tab, hide and re-show the tool window: a fresh `main` tab is created.
12. Open the non-git folder in the sandbox and click `+`: only **In this project** and **Resume a past session…** are offered.
13. Settings > Tools > Claude Chat: set the binary path to `/nonexistent`, open a new chat from the popup: the new tab shows a red "Claude Code CLI not found" notice while other tabs keep working. Clear the setting afterwards.

- [ ] **Step 3: Clean up the scratch worktrees**

```bash
cd <scratch>
git worktree remove --force .claude/worktrees/wt-1
git branch -D worktree-wt-1
```

- [ ] **Step 4: Fix any failures**

For each failing check, find the root cause (use superpowers:systematic-debugging), add a failing test when the cause is in non-UI code, fix, rerun `./gw test`, and commit with a message describing the fix.

- [ ] **Step 5: Final state**

```bash
git status --short   # expect clean
```
