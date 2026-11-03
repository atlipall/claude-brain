# Claude Chat v4 — Editor Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every message to Claude carries where the user is looking in the editor (file relative to the chat's folder, caret, selection, enclosing function), and a chip above the input shows that context live and lets the user drop it for the next message.

**Architecture:** A new package `claudechat.context`. Pure pieces: `EditorContext` / `Symbol` / `Lines` data, `Symbols` (kind word, function-like heuristic, innermost/container pick), `ContextNotes` (note paragraph and chip text) and `ContextPaths` (file → path as one chat names it, through v3's `CheckoutMapper`, which gains `checkoutOf`). Platform glue: `SymbolFinder` walks the language's Structure View model (`TreeBasedStructureViewBuilder.createStructureViewModel(null)`) down to the innermost element containing the caret; the project service `EditorContextService` tracks the last-focused local-file text editor, captures caret/selection/symbol on the EDT, and tells listeners (debounced 150 ms) when caret, selection, focus or selected tab change. `Chat.send` prepends the context note after the v1 edit note; `Chat` owns the chip state (`refreshContext`, `skipContext`) and posts a new text-only bridge message `{"type":"context","text":…,"off":…}`; the page answers with `{"type":"contextOff","off":…}`.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, WebStorm 2026.2.3 (build 262) via `local(...)`, JBR 25, JCEF, platform-bundled `kotlinx.serialization.json` (element API only), JUnit 4 + `BasePlatformTestCase`.

**Spec:** `docs/superpowers/specs/2026-11-03-editor-context-design.md` (extends `docs/superpowers/specs/2026-11-03-follow-chat-checkout-design.md`, `docs/superpowers/specs/2026-11-03-multi-chat-worktrees-design.md` and `docs/superpowers/specs/2026-11-03-claude-chat-webstorm-design.md`; everything there still applies unless v4 changes it).

## Global Constraints

- Repo `/Users/atli/projects/plugin`, branch `feature/editor-context` (starts at `a265c22`). Commit on this branch after every task.
- No system Java: run Gradle only through `./gw` (sets `JAVA_HOME` to WebStorm's JBR 25). Examples: `./gw test --tests 'claudechat.context.EditorContextTest'`, `./gw test buildPlugin`.
- Target `/Applications/WebStorm.app` (2026.2.3, build 262); `sinceBuild = "262"`. Kotlin package root `claudechat`; plugin id `local.claudechat`; tool window id `Claude Chat`.
- JSON: only the `kotlinx.serialization.json` element API. No new dependencies. The plugin's main code uses only platform API (Structure View, `ElementDescriptionUtil`), never JavaScript/TypeScript PSI classes; `plugin.xml` keeps depending on `com.intellij.modules.platform` and `com.intellij.modules.jcef` only.
- Context editor: the focused text editor, or — when focus is in the chat tool window — the last text editor that had focus in this project (tracked by the plugin); only text editors backed by a local file; otherwise no context.
- `path`: file path relative to the chat's cwd. If the file is outside the chat's checkout but has a counterpart there (v3 `CheckoutMapper.counterpart`), use the counterpart's relative path; otherwise, if the file is outside the chat's cwd and has no counterpart, no context.
- `caret`: 1-based line and column of the primary caret. `selection`: 1-based start and end lines of the primary selection, if non-empty. Multiple carets beyond the primary one are ignored.
- `symbol`: the innermost enclosing structural element at the caret — name and 1-based start–end lines — via the language-agnostic structure model; none if nothing encloses the caret or the language has no structure model. If the innermost element is not function-like, also report its nearest function-like ancestor (`container`).
- Message note, prepended after the v1 edit note, before the user's text, one paragraph, exactly this shape: ``[IDE] The user is looking at src/billing/invoice.ts, caret at line 142:17, inside function `calculateTotals` (lines 120–168). Selection: lines 140–145.`` Omit `inside …` without a symbol; use the IDE's kind word when available, otherwise ``inside `name` ``; omit `Selection: …` without a selection. The note is not shown in the user's bubble.
- Only the location is sent, never file contents. Unsaved changes are saved first (the edit note's `saveAllDocuments` runs before the context is computed).
- Chip above the message input: `<file name> › <symbol name> · L<line>` (or `<file name> · L<line>`; `· L140–145` with a selection) plus a × button; updates live, debounced 150 ms; × skips the context for the next message only and shows the muted text `context off for next message — click to restore`; after sending it returns to live; hidden when there is no context; rendered with `textContent` only.
- Setting "Attach editor context to messages", default on, in Settings > Tools > Claude Chat (`ClaudeChatSettings`, application level). Off → no chip, no note.
- Threading: `Chat.send`, chip refreshes and every `EditorContextService` call run on the EDT. The document is committed (`PsiDocumentManager.commitDocument`) and the structure model is read under `runReadActionBlocking`. No VFS refresh on the EDT: counterpart existence uses `VirtualFileSystem.findFileByPath` (never `refreshAndFindFileByPath`).
- Security properties that must survive every task: DOMPurify `SANITIZE` config and the CSP in `index.html`; the `trusted` WeakSet gating `openFile`/permission/diff clicks; capture-phase `auxclick`/`submit` guards; the bridge is injected only on the chat page and navigation away from it is blocked (`ChatPanel` `onLoadEnd` + `onBeforeBrowse`); `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` stripped in `ClaudeProcess`; `openUrl` accepts `http(s)` only.
- Tests never run the real `claude` binary (use `FakeConnection`). `BasePlatformTestCase` shares one light project across tests and project/application services persist: every test class resets the state it touches in `setUp`/`tearDown` (`Chats`, open editors, `ClaudeChatSettings.attachEditorContext`, `EditorContextService.projectDir` and `.schedule`). Light-test files live in an in-memory file system under the content root; compute it with `ProjectRootManager.getInstance(project).contentRoots.first().path`.
- JUnit 3 (`BasePlatformTestCase`) treats every no-argument `void` method whose name starts with `test` as a test, including the synthetic method Kotlin generates for a **non-capturing, no-parameter lambda inside a test method** (e.g. `Runnable { }`): the suite then fails with `Test method isn't public: testX$lambda$0`. Lambdas in test methods must take a parameter or capture something (as all lambdas in this plan do).

## Verified platform facts (build 262, checked with throw-away probe tests that have been deleted)

- **JavaScript and TypeScript are available in light tests.** With the current `build.gradle.kts` (no `bundledPlugin("JavaScript")`), `myFixture.configureByText("a.js", …)` yields language `ECMAScript 6`, `a.ts` yields `TypeScript`; the bundled plugins load at test runtime (not on the compile classpath, which is fine: the plugin only uses platform API).
- Structure model (chosen approach): `LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)` returns a `TreeBasedStructureViewBuilder` for JS (`JSStructureViewBuilderFactory$1`) and TS (`TypeScriptStructureViewBuilderFactory$1`), `null` for plain text. `createStructureViewModel(editor: Editor?)` accepts `null`; the model is `Disposable` (`Disposer.dispose(model)`). `model.root` is the file; `TreeElement.children` / `StructureViewTreeElement.value` (the `PsiElement`) / `presentation.presentableText` are public. Measured on JS: `outer(a)` → `JSFunctionImpl`, nested `inner()` → `JSFunctionImpl` child, class `K` → `ES6ClassImpl` with `m(): number` (`JSFunctionImpl`) and getter `g: number` (`JSFunctionImpl`), `const arrow = () => …` → `arrow(): number` (`JSFunctionExpressionImpl`), `const conf = {a: 1}` → `conf` (`JSVariableImpl`) with child `a` (`JSPropertyImpl`); local variables inside functions are not structure elements. TS: `calc(n: number): number` → `TypeScriptFunctionImpl`, interface method → `TypeScriptFunctionSignatureImpl`, `constructor()` → `TypeScriptFunctionImpl`.
- Labels include signatures (`calc(n: number): number`), so the plugin uses `PsiNamedElement.name` (measured: `arrow`, `f`, `o`, `outer`) and falls back to the label cut at `(`.
- Kind word: `ElementDescriptionUtil.getElementDescription(psi, UsageViewTypeLocation.INSTANCE)` (public, `intellij.platform.core` / `intellij.platform.indexing`) returns `function`, `method`, `constructor`, `class`, `interface`, `property` (also for a JS getter), `constant`, `file`.
- `StructureViewModel.getCurrentEditorElement()` also works (innermost element only, as a `PsiElement`) but needs an editor and does not give the path of ancestors; breadcrumbs (`BreadcrumbsUtil.getInfoProvider(language)` → `JSBreadcrumbsProvider`, `acceptElement`/`getElementInfo`) work too but give `outer()`-style labels and no kind. Not used.
- Listeners: `EditorFactory.getInstance().eventMulticaster` is an `EditorEventMulticasterEx`; `addCaretListener(CaretListener, Disposable)`, `addSelectionListener(SelectionListener, Disposable)` and `addFocusChangeListener(FocusChangeListener, Disposable)` (`com.intellij.openapi.editor.ex.FocusChangeListener`, overriding only `focusGained(editor: Editor)` compiles) are public. `project.messageBus.connect(disposable).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, …)` with `selectionChanged(FileEditorManagerEvent)` works.
- Debounce: `Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)` is not deprecated; its requests run on the EDT **with the write-intent lock**, so `PsiDocumentManager.commitDocument` is allowed inside them.
- `runReadAction { }` is deprecated in 262 ("Use ReadAction.nonBlocking or runReadActionBlocking"); `com.intellij.openapi.application.runReadActionBlocking { }` is not.
- After `myFixture.type(…)` the document is **not** committed; `commitDocument` fixes that.
- Light-test editors: `myFixture.editor.editorKind == EditorKind.UNTYPED` (real file tabs are `MAIN_EDITOR`; diff editors `DIFF`, consoles `CONSOLE`, previews `PREVIEW`). Light-test files are in `TempFileSystem`, and `VirtualFile.isInLocalFileSystem` is `true` for them. `FileEditorManager.getInstance(project).selectedTextEditor` returns the fixture's editor.

## File structure

```
src/main/kotlin/claudechat/context/EditorContext.kt        CREATE Lines, Symbol, EditorContext, StructureHit, Symbols, ContextNotes (pure)
src/main/kotlin/claudechat/context/ContextPaths.kt         CREATE file -> path as one chat names it (pure, uses CheckoutMapper)
src/main/kotlin/claudechat/context/SymbolFinder.kt         CREATE structure-model walk to the innermost element at an offset
src/main/kotlin/claudechat/context/EditorContextService.kt CREATE project service: context editor tracking, capture, debounced change listeners
src/main/kotlin/claudechat/follow/Checkouts.kt             MODIFY CheckoutMapper.checkoutOf(path)
src/main/kotlin/claudechat/session/Chat.kt                 MODIFY editorContext provider, note composition, skipContext, refreshContext, SessionView.context
src/main/kotlin/claudechat/session/Chats.kt                MODIFY passes the provider (setting-gated); refreshes chips on context changes
src/main/kotlin/claudechat/ui/ChatBridge.kt                MODIFY posts {"type":"context"}; handles {"type":"contextOff"}
src/main/kotlin/claudechat/ui/ChatTabs.kt                  MODIFY a cwd change also refreshes the chip
src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt  MODIFY attachEditorContext (default true)
src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt MODIFY checkbox; apply() refreshes chips
src/main/resources/webview/index.html, chat.js, chat.css   MODIFY chip #context, text only
src/test/kotlin/claudechat/context/EditorContextTest.kt    CREATE
src/test/kotlin/claudechat/context/ContextPathsTest.kt     CREATE
src/test/kotlin/claudechat/context/EditorContextServiceTest.kt CREATE
src/test/kotlin/claudechat/follow/CheckoutsTest.kt, session/ChatsTest.kt, ui/ChatBridgeTest.kt, ui/WebAssetsTest.kt,
  settings/ClaudeChatSettingsTest.kt                       MODIFY (new tests appended)
```

---

### Task 1: Context model, note and chip text, and paths per chat (pure)

**Files:**
- Create: `src/main/kotlin/claudechat/context/EditorContext.kt`
- Create: `src/main/kotlin/claudechat/context/ContextPaths.kt`
- Modify: `src/main/kotlin/claudechat/follow/Checkouts.kt:59` (add `checkoutOf` after `counterpart`)
- Test: `src/test/kotlin/claudechat/context/EditorContextTest.kt`, `src/test/kotlin/claudechat/context/ContextPathsTest.kt`, `src/test/kotlin/claudechat/follow/CheckoutsTest.kt`

**Interfaces:**
- Consumes: `claudechat.git.canonicalPath(path: String): String` (v2); `Checkout`, `Placement`, `CheckoutMapper(projectDir)` with `place(cwd)`, `counterpart(path, from, to)` (v3).
- Produces (package `claudechat.context` unless noted):
  - `data class Lines(val start: Int, val end: Int)`
  - `data class Symbol(val name: String, val kind: String?, val lines: Lines)`
  - `data class EditorContext(val path: String, val line: Int, val column: Int, val selection: Lines? = null, val symbol: Symbol? = null, val container: Symbol? = null)`
  - `data class StructureHit(val symbol: Symbol, val functionLike: Boolean)`
  - `object Symbols { fun kindWord(raw: String?): String?; fun isFunctionLike(kind: String?, psiClassName: String): Boolean; fun nameFromLabel(label: String?): String?; fun pick(path: List<StructureHit>): Pair<Symbol?, Symbol?> }`
  - `object ContextNotes { fun note(c: EditorContext): String; fun chip(c: EditorContext): String }`
  - `object ContextPaths { fun forChat(file: String, cwd: String, projectDir: String, exists: (String) -> Boolean): String? }`
  - `claudechat.follow.CheckoutMapper.checkoutOf(path: String): Checkout?`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/context/EditorContextTest.kt`:
```kotlin
package claudechat.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextTest {
    private val calc = Symbol("calculateTotals", "function", Lines(120, 168))

    @Test fun noteMatchesTheSpecExample() = assertEquals(
        "[IDE] The user is looking at src/billing/invoice.ts, caret at line 142:17, inside function `calculateTotals` (lines 120–168). Selection: lines 140–145.",
        ContextNotes.note(EditorContext("src/billing/invoice.ts", 142, 17, Lines(140, 145), calc)))

    @Test fun noteWithoutSymbolOrSelection() = assertEquals(
        "[IDE] The user is looking at README.md, caret at line 3:1.",
        ContextNotes.note(EditorContext("README.md", 3, 1)))

    @Test fun noteWithoutKindWordAndSingleLines() = assertEquals(
        "[IDE] The user is looking at a.css, caret at line 7:2, inside `.button` (line 7). Selection: line 7.",
        ContextNotes.note(EditorContext("a.css", 7, 2, Lines(7, 7), Symbol(".button", null, Lines(7, 7)))))

    @Test fun noteNamesTheEnclosingFunctionOfANonFunctionSymbol() = assertEquals(
        "[IDE] The user is looking at a.ts, caret at line 5:9, inside variable `rate` (line 5), within function `calc` (lines 1–9).",
        ContextNotes.note(EditorContext("a.ts", 5, 9, null, Symbol("rate", "variable", Lines(5, 5)), Symbol("calc", "function", Lines(1, 9)))))

    @Test fun chipText() {
        assertEquals("invoice.ts › calculateTotals · L142", ContextNotes.chip(EditorContext("src/billing/invoice.ts", 142, 17, null, calc)))
        assertEquals("invoice.ts › calculateTotals · L140–145", ContextNotes.chip(EditorContext("src/billing/invoice.ts", 142, 17, Lines(140, 145), calc)))
        assertEquals("README.md · L3", ContextNotes.chip(EditorContext("README.md", 3, 1)))
        assertEquals("README.md · L4", ContextNotes.chip(EditorContext("README.md", 4, 1, Lines(4, 4))))
    }

    @Test fun kindWords() {
        assertEquals("function", Symbols.kindWord(" Function "))
        assertNull(Symbols.kindWord(""))
        assertNull(Symbols.kindWord(null))
    }

    @Test fun functionLike() {
        assertTrue(Symbols.isFunctionLike("function", "JSFunctionImpl"))
        assertTrue(Symbols.isFunctionLike("method", "TypeScriptFunctionImpl"))
        assertTrue(Symbols.isFunctionLike("constructor", "X"))
        assertTrue(Symbols.isFunctionLike("property", "JSFunctionImpl")) // a JS getter
        assertTrue(Symbols.isFunctionLike(null, "KtNamedFunction"))
        assertTrue(Symbols.isFunctionLike(null, "PsiMethodImpl"))
        assertFalse(Symbols.isFunctionLike("class", "ES6ClassImpl"))
        assertFalse(Symbols.isFunctionLike("constant", "JSVariableImpl"))
        assertFalse(Symbols.isFunctionLike(null, "JSPropertyImpl"))
    }

    @Test fun nameFromLabel() {
        assertEquals("calc", Symbols.nameFromLabel("calc(n: number): number"))
        assertEquals("K", Symbols.nameFromLabel("K"))
        assertNull(Symbols.nameFromLabel("  "))
        assertNull(Symbols.nameFromLabel(null))
    }

    @Test fun pickInnermostAndItsFunction() {
        val fn = StructureHit(Symbol("calc", "function", Lines(1, 9)), true)
        val obj = StructureHit(Symbol("opts", "variable", Lines(2, 4)), false)
        val prop = StructureHit(Symbol("rate", "property", Lines(3, 3)), false)
        val inner = StructureHit(Symbol("inner", "function", Lines(5, 7)), true)
        assertEquals(prop.symbol to fn.symbol, Symbols.pick(listOf(fn, obj, prop)))
        assertEquals(inner.symbol to null, Symbols.pick(listOf(fn, inner)))
        assertEquals(obj.symbol to null, Symbols.pick(listOf(obj)))
        assertEquals(null to null, Symbols.pick(emptyList()))
    }
}
```

`src/test/kotlin/claudechat/context/ContextPathsTest.kt`:
```kotlin
package claudechat.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ContextPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val all = { _: String -> true }
    private val none = { _: String -> false }

    private fun forChat(file: String, cwd: String, exists: (String) -> Boolean = all) = ContextPaths.forChat(file, cwd, "/p", exists)

    @Test fun fileInTheChatsFolder() {
        assertEquals("src/a.ts", forChat("/p/src/a.ts", "/p", none))
        assertEquals("lib/b.ts", forChat("/p/.claude/worktrees/wt1/lib/b.ts", "/p/.claude/worktrees/wt1", none))
    }

    @Test fun worktreeChatNamesTheCounterpartOfAMainFile() {
        val asked = mutableListOf<String>()
        assertEquals("src/a.ts", forChat("/p/src/a.ts", "/p/.claude/worktrees/wt1") { asked += it; true })
        assertEquals(listOf("/p/.claude/worktrees/wt1/src/a.ts"), asked)
    }

    @Test fun mainChatNamesTheCounterpartOfAWorktreeFile() =
        assertEquals("src/a.ts", forChat("/p/.claude/worktrees/wt1/src/a.ts", "/p"))

    @Test fun worktreeToWorktree() =
        assertEquals("a.ts", forChat("/p/.claude/worktrees/wt2/a.ts", "/p/.claude/worktrees/wt1"))

    @Test fun noCounterpartNoContext() {
        assertNull(forChat("/p/src/new.ts", "/p/.claude/worktrees/wt1", none))
        assertNull(forChat("/p/.claude/worktrees/wt1/new.ts", "/p", none))
    }

    @Test fun filesOutsideTheChatsFolderHaveNoContext() {
        assertNull(forChat("/other/a.ts", "/p"))
        assertNull(forChat("/other/a.ts", "/p/.claude/worktrees/wt1"))
        assertNull(forChat("/p", "/p"))
    }

    @Test fun chatOutsideTheProjectUsesItsOwnFolder() {
        assertEquals("src/a.ts", forChat("/elsewhere/wt/src/a.ts", "/elsewhere/wt"))
        assertNull(forChat("/p/src/a.ts", "/elsewhere/wt"))
    }

    @Test fun pathsAreComparedCanonically() {
        val project = tmp.newFolder("proj")
        File(project, "src").mkdirs()
        File(project, "src/a.ts").writeText("")
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), project.toPath())
        assertEquals("src/a.ts", ContextPaths.forChat("${link.path}/src/a.ts", project.path + "/", link.path, all))
    }
}
```

Append to `src/test/kotlin/claudechat/follow/CheckoutsTest.kt`, before the final closing `}` of the class:
```kotlin

    @Test fun checkoutOfAFile() {
        assertEquals(main, mapper.checkoutOf("/p/src/a.ts"))
        assertEquals(wt1, mapper.checkoutOf("/p/.claude/worktrees/wt1/src/a.ts"))
        assertNull(mapper.checkoutOf("/p/.claude/worktrees/a.ts"))
        assertNull(mapper.checkoutOf("/p/.claude/worktrees/main/a.ts"))
        assertNull(mapper.checkoutOf("/other/a.ts"))
        assertNull(mapper.checkoutOf("/p"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.context.EditorContextTest' --tests 'claudechat.context.ContextPathsTest' --tests 'claudechat.follow.CheckoutsTest'`
Expected: FAIL — `compileTestKotlin` reports `e: …/EditorContextTest.kt… Unresolved reference 'ContextNotes'.`, `Unresolved reference 'ContextPaths'.`, `Unresolved reference 'checkoutOf'.` and `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/context/EditorContext.kt`:
```kotlin
package claudechat.context

/** A 1-based, inclusive line range. */
data class Lines(val start: Int, val end: Int)

/** A structural element around the caret: its name, the IDE's kind word ("function", "method", "class", …) or null, and its lines. */
data class Symbol(val name: String, val kind: String?, val lines: Lines)

/**
 * Where the user is looking. [path] is absolute when captured and, once resolved for a chat ([ContextPaths.forChat]),
 * relative to that chat's folder. [line] and [column] are 1-based (the column counts characters). [container] is the
 * nearest function-like element around [symbol], set only when [symbol] itself is not function-like.
 */
data class EditorContext(
    val path: String,
    val line: Int,
    val column: Int,
    val selection: Lines? = null,
    val symbol: Symbol? = null,
    val container: Symbol? = null,
)

/** One structural element on the way from the outermost element around the caret to the innermost one. */
data class StructureHit(val symbol: Symbol, val functionLike: Boolean)

object Symbols {
    private val FUNCTION_WORDS = listOf("function", "method", "constructor", "lambda", "closure")
    private val FUNCTION_CLASS_PARTS = listOf("Function", "Method", "Lambda", "Constructor")

    /** The IDE's kind word as the note shows it: trimmed and lower case; null when the IDE has none. */
    fun kindWord(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Function-like: the kind word names a function, or the PSI class does (a JS getter's kind word is "property",
     * its class `JSFunctionImpl`; Kotlin's is `KtNamedFunction`, Java's `PsiMethodImpl`).
     */
    fun isFunctionLike(kind: String?, psiClassName: String): Boolean =
        (kind != null && FUNCTION_WORDS.any { it in kind }) || FUNCTION_CLASS_PARTS.any { it in psiClassName }

    /** A name from a Structure View label: `calc(n: number): number` -> `calc`. */
    fun nameFromLabel(label: String?): String? = label?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * [path] runs from the outermost element to the innermost. Returns the innermost element and, when that one is not
     * function-like, the nearest function-like element around it.
     */
    fun pick(path: List<StructureHit>): Pair<Symbol?, Symbol?> {
        val innermost = path.lastOrNull() ?: return null to null
        if (innermost.functionLike) return innermost.symbol to null
        return innermost.symbol to path.dropLast(1).lastOrNull { it.functionLike }?.symbol
    }
}

/** The text Claude and the chip get for an [EditorContext] whose path is already relative to the chat's folder. */
object ContextNotes {
    /** The paragraph prepended to the user's message. Location only, never file contents. */
    fun note(c: EditorContext): String = buildString {
        append("[IDE] The user is looking at ${c.path}, caret at line ${c.line}:${c.column}")
        c.symbol?.let { append(", inside ").append(describe(it)) }
        c.container?.let { append(", within ").append(describe(it)) }
        append('.')
        c.selection?.let { append(" Selection: ").append(lines(it)).append('.') }
    }

    /** The chip above the input: `invoice.ts › calculateTotals · L142`, or `· L140–145` while lines are selected. */
    fun chip(c: EditorContext): String {
        val file = c.path.substringAfterLast('/')
        val symbol = c.symbol?.let { " › ${it.name}" }.orEmpty()
        val where = c.selection?.let { if (it.start == it.end) "L${it.start}" else "L${it.start}–${it.end}" } ?: "L${c.line}"
        return "$file$symbol · $where"
    }

    private fun describe(s: Symbol) = (s.kind?.let { "$it " } ?: "") + "`${s.name}` (${lines(s.lines)})"

    private fun lines(l: Lines) = if (l.start == l.end) "line ${l.start}" else "lines ${l.start}–${l.end}"
}
```

`src/main/kotlin/claudechat/context/ContextPaths.kt`:
```kotlin
package claudechat.context

import claudechat.follow.CheckoutMapper
import claudechat.follow.Placement
import claudechat.git.canonicalPath

object ContextPaths {
    /**
     * [file] as the chat working in [cwd] names it, comparing canonical paths. When the chat's folder is a checkout of
     * the project at [projectDir] and [file] lies in another checkout, the answer is the file's counterpart in the
     * chat's checkout, if [exists] says it is there. Otherwise it is [file] relative to the chat's folder. Null when
     * neither applies, including a worktree file with no counterpart seen from main (main does not own
     * `.claude/worktrees`, as in v1's edit notes).
     */
    fun forChat(file: String, cwd: String, projectDir: String, exists: (String) -> Boolean): String? {
        val mapper = CheckoutMapper(projectDir)
        val path = canonicalPath(file)
        val chat = (mapper.place(cwd) as? Placement.Followable)?.checkout
        val home = chat?.let { mapper.checkoutOf(path) }
        if (chat != null && home != null && home != chat) {
            return mapper.counterpart(path, home, chat)?.takeIf(exists)?.let { relativeTo(it, chat.dir) }
        }
        return relativeTo(path, chat?.dir ?: canonicalPath(cwd))
    }

    private fun relativeTo(path: String, dir: String): String? =
        path.removePrefix(dir.trimEnd('/') + "/").takeIf { it != path && it.isNotEmpty() }
}
```

In `src/main/kotlin/claudechat/follow/Checkouts.kt` replace lines 58-59:
```kotlin
        return to.dir.trimEnd('/') + "/" + relative
    }
```
with:
```kotlin
        return to.dir.trimEnd('/') + "/" + relative
    }

    /** The checkout [path] lies in: the worktree for files under `<project>/.claude/worktrees/<name>/`, main for other files in the project, else null. */
    fun checkoutOf(path: String): Checkout? {
        val file = canonicalPath(path)
        if (file.startsWith(worktrees)) {
            val name = file.removePrefix(worktrees).substringBefore('/', missingDelimiterValue = "")
            return if (name.isEmpty()) null else (place(worktrees + name) as? Placement.Followable)?.checkout
        }
        return if (file.startsWith("$project/")) main else null
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.context.EditorContextTest' --tests 'claudechat.context.ContextPathsTest' --tests 'claudechat.follow.CheckoutsTest'`
Expected: PASS — `BUILD SUCCESSFUL`; `build/test-results/test/` shows EditorContextTest `tests="9"`, ContextPathsTest `tests="8"`, CheckoutsTest `tests="9"`, all `failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/context/EditorContext.kt src/main/kotlin/claudechat/context/ContextPaths.kt \
  src/main/kotlin/claudechat/follow/Checkouts.kt \
  src/test/kotlin/claudechat/context/EditorContextTest.kt src/test/kotlin/claudechat/context/ContextPathsTest.kt \
  src/test/kotlin/claudechat/follow/CheckoutsTest.kt
git commit -m "Format editor context notes and chips, and name files per chat"
```

---

### Task 2: Capture the editor context (context editor, caret, selection, structure symbol)

**Files:**
- Create: `src/main/kotlin/claudechat/context/SymbolFinder.kt`
- Create: `src/main/kotlin/claudechat/context/EditorContextService.kt`
- Test: `src/test/kotlin/claudechat/context/EditorContextServiceTest.kt`

**Interfaces:**
- Consumes: `Lines`, `Symbol`, `EditorContext`, `StructureHit`, `Symbols.kindWord`, `Symbols.isFunctionLike`, `Symbols.nameFromLabel`, `Symbols.pick`, `ContextPaths.forChat` (Task 1).
- Produces:
  - `object SymbolFinder { fun find(file: PsiFile, document: Document, offset: Int): Pair<Symbol?, Symbol?> }` (read action, committed document).
  - `@Service(PROJECT) class EditorContextService(project: Project) : Disposable` with
    `var projectDir: String`, `var schedule: (Runnable) -> Unit`, `fun editorFocused(editor: Editor)`,
    `fun addListener(parent: Disposable, listener: () -> Unit)`, `fun capture(): EditorContext?` (absolute path),
    `fun contextFor(cwd: String): EditorContext?` (path as that chat names it), `companion { const val DEBOUNCE_MS = 150; fun getInstance(project: Project): EditorContextService }`. All EDT.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/context/EditorContextServiceTest.kt`:
```kotlin
package claudechat.context

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorContextServiceTest : BasePlatformTestCase() {
    private val service get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private lateinit var savedDir: String
    private lateinit var savedSchedule: (Runnable) -> Unit

    // Lines 1-11; JS lists functions, classes, methods, top-level variables and object properties in its Structure View.
    private val js = listOf(
        "function outer(a) {",
        "  const x = 1;",
        "  function inner() {",
        "    return x;",
        "  }",
        "  return inner();",
        "}",
        "class K {",
        "  m() { return 1; }",
        "}",
        "const conf = { a: 1 };",
    ).joinToString("\n", postfix = "\n")

    override fun setUp() {
        super.setUp()
        manager.openFiles.forEach(manager::closeFile) // the light project and its services are shared between tests
        savedDir = service.projectDir
        savedSchedule = service.schedule
        service.projectDir = root
        service.schedule = { it.run() }
    }

    override fun tearDown() {
        try {
            manager.openFiles.forEach(manager::closeFile)
            service.projectDir = savedDir
            service.schedule = savedSchedule
        } finally {
            super.tearDown()
        }
    }

    fun testCaretInANestedFunction() {
        myFixture.configureByText("calc.js", js.replace("return x;", "return <caret>x;"))
        val context = service.capture()!!
        assertEquals(myFixture.file.virtualFile.path, context.path)
        assertEquals(4, context.line)
        assertEquals(12, context.column)
        assertNull(context.selection)
        assertEquals(Symbol("inner", "function", Lines(3, 5)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretInAClassMethod() {
        myFixture.configureByText("calc.js", js.replace("return 1;", "return <caret>1;"))
        assertEquals(Symbol("m", "method", Lines(9, 9)), service.capture()!!.symbol)
    }

    fun testCaretInAPropertyOutsideAnyFunction() {
        myFixture.configureByText("calc.js", js.replace("a: 1", "a: <caret>1"))
        val context = service.capture()!!
        assertEquals(Symbol("a", "property", Lines(11, 11)), context.symbol)
        assertNull(context.container)
    }

    fun testCaretOutsideEveryElement() {
        myFixture.configureByText("calc.js", "// notes<caret>\n\nfunction f() {}\n")
        assertNull(service.capture()!!.symbol)
    }

    fun testTypeScriptMethod() {
        myFixture.configureByText("c.ts", "export class C {\n  total(n: number): number {\n    return <caret>n;\n  }\n}\n")
        assertEquals(Symbol("total", "method", Lines(2, 4)), service.capture()!!.symbol)
    }

    fun testSelectionLines() {
        myFixture.configureByText("calc.js", js.replace("  const x", "<selection>  const x").replace("return x;", "return x;<caret></selection>"))
        assertEquals(Lines(2, 4), service.capture()!!.selection)
    }

    fun testSelectionEndingAtALineStartStopsOnThePreviousLine() {
        myFixture.configureByText("calc.js", js.replace("  const x = 1;\n", "<selection>  const x = 1;\n<caret></selection>"))
        assertEquals(Lines(2, 2), service.capture()!!.selection)
    }

    fun testPlainTextHasALocationButNoSymbol() {
        myFixture.configureByText("notes.txt", "one\ntw<caret>o\n")
        assertEquals(EditorContext(myFixture.file.virtualFile.path, 2, 3), service.capture())
    }

    fun testNoEditorNoContext() = assertNull(service.capture())

    fun testTheLastFocusedEditorWins() {
        myFixture.configureByText("a.txt", "a")
        val a = myFixture.editor
        manager.openFile(myFixture.addFileToProject("b.txt", "b").virtualFile, false)
        val b = (manager.getSelectedEditor(myFixture.findFileInTempDir("b.txt")) as TextEditor).editor
        service.editorFocused(b)
        assertTrue(service.capture()!!.path.endsWith("/b.txt"))
        service.editorFocused(a)
        assertTrue(service.capture()!!.path.endsWith("/a.txt"))
    }

    fun testEditorsWithoutALocalFileAreIgnored() {
        myFixture.configureByText("a.txt", "a")
        val factory = EditorFactory.getInstance()
        val scratch = factory.createEditor(factory.createDocument("scratch"), project)
        try {
            service.editorFocused(scratch)
            assertTrue(service.capture()!!.path.endsWith("/a.txt"))
        } finally {
            factory.releaseEditor(scratch)
        }
    }

    fun testCaretAndSelectionChangesNotifyListeners() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        var fired = 0
        service.addListener(testRootDisposable) { fired++ }
        myFixture.editor.caretModel.moveToOffset(5)
        assertTrue(fired > 0)
        val afterCaret = fired
        myFixture.editor.selectionModel.setSelection(0, 3)
        assertTrue(fired > afterCaret)
    }

    fun testListenerIsRemovedWithItsParent() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        val parent = Disposer.newDisposable()
        var fired = 0
        service.addListener(parent) { fired++ }
        Disposer.dispose(parent)
        myFixture.editor.caretModel.moveToOffset(5)
        assertEquals(0, fired)
    }

    fun testContextForAChatUsesItsFolderOrTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.js", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.js", "function f() {}\n").virtualFile)
        assertEquals("src/a.js", service.contextFor(root)!!.path)
        assertEquals("src/a.js", service.contextFor("$root/.claude/worktrees/wt1")!!.path)
        assertNull(service.contextFor("$root/.claude/worktrees/wt2"))
        assertNull(service.contextFor("/elsewhere"))
        assertEquals("a.js", service.contextFor("$root/src")!!.path)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.context.EditorContextServiceTest'`
Expected: FAIL — `e: …/EditorContextServiceTest.kt… Unresolved reference 'EditorContextService'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/claudechat/context/SymbolFinder.kt`:
```kotlin
package claudechat.context

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.usageView.UsageViewTypeLocation

/**
 * Finds the structural elements around an offset through the file's Structure View model, which every language with a
 * Structure View provides (JavaScript and TypeScript in WebStorm). Call in a read action, with the document committed.
 */
object SymbolFinder {
    private const val MAX_DEPTH = 32

    /** The innermost element at [offset] and its function-like container (see [Symbols.pick]); both null without a structure model. */
    fun find(file: PsiFile, document: Document, offset: Int): Pair<Symbol?, Symbol?> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: return null to null
        val model = builder.createStructureViewModel(null)
        return try {
            Symbols.pick(path(model.root, document, offset))
        } catch (_: IndexNotReadyException) {
            null to null // some structure models need indexes; while indexing there is no symbol
        } finally {
            Disposer.dispose(model)
        }
    }

    /** From the root's children down: at each level the smallest child whose element contains [offset]. */
    private fun path(root: TreeElement, document: Document, offset: Int): List<StructureHit> {
        val hits = mutableListOf<StructureHit>()
        var node = root
        repeat(MAX_DEPTH) {
            val (child, psi) = node.children
                .mapNotNull { element -> psiOf(element)?.takeIf { it.textRange?.containsOffset(offset) == true }?.let { element to it } }
                .minByOrNull { candidate -> candidate.second.textRange.length }
                ?: return hits
            hit(child, psi, document)?.let(hits::add)
            node = child
        }
        return hits
    }

    private fun psiOf(element: TreeElement): PsiElement? =
        ((element as? StructureViewTreeElement)?.value as? PsiElement)?.takeIf { it.isValid }

    private fun hit(element: TreeElement, psi: PsiElement, document: Document): StructureHit? {
        val name = (psi as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() }
            ?: Symbols.nameFromLabel(element.presentation.presentableText)
            ?: return null
        val kind = Symbols.kindWord(ElementDescriptionUtil.getElementDescription(psi, UsageViewTypeLocation.INSTANCE))
        val range = psi.textRange
        val start = range.startOffset.coerceIn(0, document.textLength)
        val last = (range.endOffset - 1).coerceIn(start, document.textLength) // end offsets are exclusive
        val lines = Lines(document.getLineNumber(start) + 1, document.getLineNumber(last) + 1)
        return StructureHit(Symbol(name, kind, lines), Symbols.isFunctionLike(kind, psi.javaClass.simpleName))
    }
}
```

`src/main/kotlin/claudechat/context/EditorContextService.kt`:
```kotlin
package claudechat.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx
import com.intellij.openapi.editor.ex.FocusChangeListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows where the user is looking. The context editor is the text editor of this project that last had focus (focus
 * moving into the chat keeps it), or the selected text editor before any had focus; only editors of local files count.
 * Listeners hear, debounced, when the caret, selection, focused editor or selected tab changed. EDT only.
 */
@Service(Service.Level.PROJECT)
class EditorContextService(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastFocused: Editor? = null

    /** The main checkout's folder, for counterpart paths (tests point it at the light project's content root). */
    var projectDir: String = project.basePath ?: System.getProperty("user.home")

    /** Runs a round of listener calls once the editor has been quiet for [DEBOUNCE_MS] (tests run it at once). */
    var schedule: (Runnable) -> Unit = { round ->
        alarm.cancelAllRequests()
        alarm.addRequest(round, DEBOUNCE_MS)
    }

    init {
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = changed(event.editor)
        }, this)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = changed(e.editor)
        }, this)
        (multicaster as? EditorEventMulticasterEx)?.addFocusChangeListener(object : FocusChangeListener {
            override fun focusGained(editor: Editor) = editorFocused(editor)
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                // A tab switch without focus (or v3 swapping editors to another checkout); a non-text tab means no context.
                lastFocused = (event.newEditor as? TextEditor)?.editor?.takeIf(::accepts)
                changed(null)
            }
        })
    }

    /** [editor] got focus: it becomes the context editor if it is a text editor of a local file in this project. */
    fun editorFocused(editor: Editor) {
        if (!accepts(editor)) return
        lastFocused = editor
        changed(editor)
    }

    /** [listener] runs on the EDT after the context may have changed. Removed when [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Where the user is looking, with the file's absolute path; null without a context editor. Commits the document. */
    fun capture(): EditorContext? {
        val editor = contextEditor() ?: return null
        val file = localFile(editor) ?: return null
        return locate(editor, file.path)
    }

    /** [capture] as the chat working in [cwd] names the file ([ContextPaths.forChat]); null when it has no name for it. */
    fun contextFor(cwd: String): EditorContext? {
        val editor = contextEditor() ?: return null
        val file = localFile(editor) ?: return null
        val path = ContextPaths.forChat(file.path, cwd, projectDir) { file.fileSystem.findFileByPath(it) != null } ?: return null
        return locate(editor, path)
    }

    private fun contextEditor(): Editor? =
        lastFocused?.takeIf(::accepts) ?: FileEditorManager.getInstance(project).selectedTextEditor?.takeIf(::accepts)

    private fun locate(editor: Editor, path: String): EditorContext {
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val offset = caret.offset
        val line = document.getLineNumber(offset)
        val selection = if (caret.hasSelection()) {
            val start = caret.selectionStart
            // A selection that ends at the start of a line stops on the line before it.
            val last = if (caret.selectionEnd > start) caret.selectionEnd - 1 else start
            Lines(document.getLineNumber(start) + 1, document.getLineNumber(last) + 1)
        } else {
            null
        }
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val (symbol, container) = runReadActionBlocking {
            documents.getPsiFile(document)?.let { SymbolFinder.find(it, document, offset) } ?: (null to null)
        }
        return EditorContext(path, line + 1, offset - document.getLineStartOffset(line) + 1, selection, symbol, container)
    }

    /** File tabs are MAIN_EDITOR; light-test editors are UNTYPED. Diff, console and preview editors never count. */
    private fun accepts(editor: Editor): Boolean =
        !editor.isDisposed && editor.project === project &&
            (editor.editorKind == EditorKind.MAIN_EDITOR || editor.editorKind == EditorKind.UNTYPED) &&
            localFile(editor) != null

    private fun localFile(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isValid && it.isInLocalFileSystem }

    private fun changed(editor: Editor?) {
        if (editor != null && editor.project !== project) return
        if (listeners.isEmpty()) return
        schedule(Runnable { if (!project.isDisposed) listeners.forEach { it() } })
    }

    override fun dispose() = Unit

    companion object {
        const val DEBOUNCE_MS = 150

        fun getInstance(project: Project): EditorContextService = project.service()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.context.EditorContextServiceTest'`
Expected: PASS — `BUILD SUCCESSFUL`; EditorContextServiceTest `tests="14" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/context/SymbolFinder.kt src/main/kotlin/claudechat/context/EditorContextService.kt \
  src/test/kotlin/claudechat/context/EditorContextServiceTest.kt
git commit -m "Track the context editor and find the symbol at its caret"
```

---

### Task 3: Prepend the context note on send, with the setting and skip-once

**Files:**
- Modify: `src/main/kotlin/claudechat/session/Chat.kt:1-5` (imports), `:19-27` (constructor), `:106-114` (`send`)
- Modify: `src/main/kotlin/claudechat/session/Chats.kt:41` (cache the service), `:50-58` (provider)
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt:20`
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt:42-46`
- Test: `src/test/kotlin/claudechat/session/ChatsTest.kt`, `src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt`

**Interfaces:**
- Consumes: `EditorContext`, `ContextNotes.note` (Task 1); `EditorContextService.getInstance(project)`, `.contextFor(cwd)`, `.projectDir` (Task 2).
- Produces:
  - `Chat(…, editNote: (Chat) -> String?, editorContext: (Chat) -> EditorContext? = { null }, log: (String) -> Unit = {})`.
  - `Chat.contextOff: Boolean` (read-only outside), `Chat.skipContext(off: Boolean)` (EDT).
  - `ClaudeChatSettings.State.attachEditorContext: Boolean` (default `true`).
  - Sent payload: `<edit note>\n\n<context note>\n\n<text>`, either note omitted when absent.

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/claudechat/session/ChatsTest.kt` replace lines 3-11:
```kotlin
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
```
with:
```kotlin
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.context.EditorContextService
import claudechat.settings.ClaudeChatSettings
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
```

Replace lines 31-42 (`setUp` and `tearDown`):
```kotlin
    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        chats.store = SessionStore(home)
        FileDocumentManager.getInstance().saveAllDocuments() // chats opened below only hear about later saves
    }

    override fun tearDown() {
        try { chats.closeAll(); home.deleteRecursively() } finally { super.tearDown() }
    }
```
with:
```kotlin
    private val settings get() = ClaudeChatSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedProjectDir: String

    override fun setUp() {
        super.setUp()
        chats.closeAll() // the light project and its services are shared between tests
        manager.openFiles.forEach(manager::closeFile) // a leftover editor would add editor context to every message
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedProjectDir = contextService.projectDir
        contextService.projectDir = root
        home = Files.createTempDirectory("claude-home").toFile()
        chats.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        chats.store = SessionStore(home)
        FileDocumentManager.getInstance().saveAllDocuments() // chats opened below only hear about later saves
    }

    override fun tearDown() {
        try {
            chats.closeAll()
            home.deleteRecursively()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.projectDir = savedProjectDir
        } finally {
            super.tearDown()
        }
    }
```

Append before the final closing `}` of `ChatsTest`:
```kotlin

    fun testSendPrefixesEditorContext() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("what does this do?")
        assertEquals(
            listOf("[IDE] The user is looking at calc.js, caret at line 2:11, inside function `total` (lines 1–3).\n\nwhat does this do?"),
            connections.single().userMessages())
    }

    fun testEditNoteComesBeforeEditorContext() {
        val chat = chats.open(ChatSpec.inProject(root))
        myFixture.configureByText("a.txt", "x")
        myFixture.type("y")
        chat.send("hello")
        assertEquals(
            listOf("[IDE] Since your last turn the user edited these files: a.txt.\nRe-read them before relying on earlier contents.\n\n" +
                "[IDE] The user is looking at a.txt, caret at line 1:2.\n\nhello"),
            connections.single().userMessages())
    }

    fun testSkippedContextIsLeftOutOnceThenComesBack() {
        myFixture.configureByText("a.txt", "x")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.skipContext(true)
        chat.send("one")
        assertFalse(chat.contextOff)
        connections.single().emit(Fx.result())
        chat.send("two")
        assertEquals(listOf("one", "[IDE] The user is looking at a.txt, caret at line 1:1.\n\ntwo"), connections.single().userMessages())
    }

    fun testSkipCanBeUndone() {
        myFixture.configureByText("a.txt", "x")
        val chat = chats.open(ChatSpec.inProject(root))
        chat.skipContext(true)
        chat.skipContext(false)
        chat.send("one")
        assertEquals(listOf("[IDE] The user is looking at a.txt, caret at line 1:1.\n\none"), connections.single().userMessages())
    }

    fun testSettingOffSendsNoContext() {
        myFixture.configureByText("a.txt", "x")
        settings.attachEditorContext = false
        val chat = chats.open(ChatSpec.inProject(root))
        chat.send("hello")
        assertEquals(listOf("hello"), connections.single().userMessages())
    }

    fun testWorktreeChatNamesTheCounterpart() {
        myFixture.addFileToProject(".claude/worktrees/wt1/src/a.txt", "x")
        myFixture.openFileInEditor(myFixture.addFileToProject("src/a.txt", "x").virtualFile)
        val wt1 = chats.open(ChatSpec("wt1", "$root/.claude/worktrees/wt1"))
        val wt2 = chats.open(ChatSpec("wt2", "$root/.claude/worktrees/wt2"))
        wt1.send("a")
        wt2.send("b")
        assertEquals(listOf("[IDE] The user is looking at src/a.txt, caret at line 1:1.\n\na"), connections[0].userMessages())
        assertEquals(listOf("b"), connections[1].userMessages())
    }
```

Replace the body of `src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt` line 7:
```kotlin
    @Test fun followingIsOnByDefault() = assertTrue(ClaudeChatSettings.State().followCheckout)
```
with:
```kotlin
    @Test fun followingIsOnByDefault() = assertTrue(ClaudeChatSettings.State().followCheckout)

    @Test fun editorContextIsOnByDefault() = assertTrue(ClaudeChatSettings.State().attachEditorContext)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.ChatsTest' --tests 'claudechat.settings.ClaudeChatSettingsTest'`
Expected: FAIL — `e: … Unresolved reference 'attachEditorContext'.` and `Unresolved reference 'skipContext'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the implementation**

In `src/main/kotlin/claudechat/session/Chat.kt` replace lines 3-5:
```kotlin
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
```
with:
```kotlin
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.context.ContextNotes
import claudechat.context.EditorContext
```

Replace the constructor (lines 19-27):
```kotlin
class Chat(
    val id: Int,
    spec: ChatSpec,
    history: List<ChatItem>,
    connector: (SessionLaunch) -> CliConnection,
    initialMode: PermissionMode?,
    private val editNote: (Chat) -> String?,
    log: (String) -> Unit = {},
) {
```
with:
```kotlin
class Chat(
    val id: Int,
    spec: ChatSpec,
    history: List<ChatItem>,
    connector: (SessionLaunch) -> CliConnection,
    initialMode: PermissionMode?,
    private val editNote: (Chat) -> String?,
    /** Where the user is looking, as this chat names the file; null for none (or when the setting is off). EDT. */
    private val editorContext: (Chat) -> EditorContext? = { null },
    log: (String) -> Unit = {},
) {
```

Replace `send` (lines 106-114):
```kotlin
    /** EDT only (the edit note saves documents). Ignored while the session is dead. */
    fun send(text: String) {
        if (session.state == SessionState.DEAD) return
        if (firstMessage == null) {
            firstMessage = text
            onTitleChanged?.invoke()
        }
        session.send(text, editNote(this))
    }
```
with:
```kotlin
    /** True after the user turned editor context off for the next message only. EDT only. */
    var contextOff = false
        private set

    /** Leaves the editor context out of the next message ([off]) or puts it back. EDT only. */
    fun skipContext(off: Boolean) {
        contextOff = off
    }

    /**
     * EDT only (the edit note saves documents, the context reads the editor). Ignored while the session is dead.
     * Claude gets the edit note, then the editor-context note, then [text]; the chat shows only [text].
     */
    fun send(text: String) {
        if (session.state == SessionState.DEAD) return
        if (firstMessage == null) {
            firstMessage = text
            onTitleChanged?.invoke()
        }
        val edits = editNote(this) // saves documents first
        val context = if (contextOff) null else editorContext(this)?.let(ContextNotes::note)
        contextOff = false
        session.send(text, listOfNotNull(edits, context).joinToString("\n\n").ifEmpty { null })
    }
```

In `src/main/kotlin/claudechat/session/Chats.kt` replace line 10:
```kotlin
import claudechat.ide.EditTracker
```
with:
```kotlin
import claudechat.context.EditorContextService
import claudechat.ide.EditTracker
```
Replace line 41:
```kotlin
    private val tracker = EditTracker.getInstance(project) // also starts recording saves before the first message
```
with:
```kotlin
    private val tracker = EditTracker.getInstance(project) // also starts recording saves before the first message
    private val context = EditorContextService.getInstance(project) // also starts tracking the focused editor
```
Replace the `Chat(...)` call in `open` (lines 50-58):
```kotlin
        val chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { c -> tracker.consumeNote(c.id, c.cwd) },
            log = { LOG.info(it) },
        )
```
with:
```kotlin
        val chat = Chat(
            id = ids.incrementAndGet(),
            spec = spec,
            history = history,
            connector = connector,
            initialMode = defaultMode(),
            editNote = { c -> tracker.consumeNote(c.id, c.cwd) },
            editorContext = { c -> if (ClaudeChatSettings.getInstance().state.attachEditorContext) context.contextFor(c.cwd) else null },
            log = { LOG.info(it) },
        )
```

In `src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt` replace line 20:
```kotlin
        var followCheckout by property(true)
```
with:
```kotlin
        var followCheckout by property(true)
        /** Messages tell Claude which file, line, selection and function the editor shows (location only). */
        var attachEditorContext by property(true)
```

In `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt` replace lines 42-46:
```kotlin
        row {
            checkBox("IDE follows the selected chat's checkout")
                .bindSelected({ state.followCheckout }, { state.followCheckout = it })
                .comment("Selecting a worktree chat scopes the Project view and Find in Files to that worktree and swaps open editors to its files.")
        }
```
with:
```kotlin
        row {
            checkBox("IDE follows the selected chat's checkout")
                .bindSelected({ state.followCheckout }, { state.followCheckout = it })
                .comment("Selecting a worktree chat scopes the Project view and Find in Files to that worktree and swaps open editors to its files.")
        }
        row {
            checkBox("Attach editor context to messages")
                .bindSelected({ state.attachEditorContext }, { state.attachEditorContext = it })
                .comment("Tells Claude the file, caret line, selection and enclosing function of the editor you last used. Never the code itself.")
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.ChatsTest' --tests 'claudechat.settings.ClaudeChatSettingsTest' --tests 'claudechat.ui.ChatBridgeTest'`
Expected: PASS — `BUILD SUCCESSFUL`; ChatsTest `tests="21"`, ClaudeChatSettingsTest `tests="2"`, ChatBridgeTest `tests="12"`, all 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/session/Chat.kt src/main/kotlin/claudechat/session/Chats.kt \
  src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt \
  src/test/kotlin/claudechat/session/ChatsTest.kt src/test/kotlin/claudechat/settings/ClaudeChatSettingsTest.kt
git commit -m "Tell Claude where the user is looking with every message"
```

---

### Task 4: The context chip (live, debounced, × skips the next message)

**Files:**
- Modify: `src/main/kotlin/claudechat/session/Chat.kt` (as left by Task 3: `SessionView`, `attach`, `skipContext`, `send`)
- Modify: `src/main/kotlin/claudechat/session/Chats.kt` (as left by Task 3: an `init` block after the cached services)
- Modify: `src/main/kotlin/claudechat/ui/ChatBridge.kt:3-4` (import), `:66` (handle `contextOff`), `:101` (post `context`)
- Modify: `src/main/kotlin/claudechat/ui/ChatTabs.kt:33`
- Modify: `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt:3-22` (as left by Task 3)
- Modify: `src/main/resources/webview/index.html:22`, `src/main/resources/webview/chat.js:11,55,177,254`, `src/main/resources/webview/chat.css` (append)
- Test: `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`, `src/test/kotlin/claudechat/ui/WebAssetsTest.kt`

**Interfaces:**
- Consumes: `ContextNotes.chip` (Task 1); `EditorContextService.addListener(parent, listener)`, `.schedule` (Task 2); `Chat.editorContext`, `Chat.contextOff`, `Chat.skipContext`, `ClaudeChatSettings.State.attachEditorContext` (Task 3).
- Produces:
  - `SessionView.context(text: String, off: Boolean)` (default no-op): empty [text] hides the chip.
  - `Chat.refreshContext()` (EDT): recomputes the chip, posts only when `(text, off)` changed.
  - Bridge out: `{"type":"context","text":String,"off":Boolean}`; bridge in: `{"type":"contextOff","off":Boolean}`.
  - Page: `<div id="context" hidden><span id="context-text"></span><button id="context-off" …>×</button></div>`, JS `setContext(text, off)`.

- [ ] **Step 1: Write the failing tests**

In `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt` replace lines 3-16 (imports):
```kotlin
import claudechat.cli.arr
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.PromptHistory
import claudechat.session.SessionState
import claudechat.testutil.FakeConnection
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
```
with:
```kotlin
import claudechat.cli.arr
import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.context.EditorContextService
import claudechat.session.Chat
import claudechat.session.ChatSpec
import claudechat.session.Chats
import claudechat.session.PromptHistory
import claudechat.session.SessionState
import claudechat.settings.ClaudeChatSettings
import claudechat.testutil.FakeConnection
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
```

Replace lines 25-38 (`root`, `setUp`, `tearDown`):
```kotlin
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path

    override fun setUp() {
        super.setUp()
        PromptHistory.getInstance(project).clear()
        chats.closeAll()
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try { bridge.dispose(); chat.detach(bridge); chats.closeAll() } finally { super.tearDown() }
    }
```
with:
```kotlin
    private val root get() = ProjectRootManager.getInstance(project).contentRoots.first().path
    private val settings get() = ClaudeChatSettings.getInstance().state
    private val contextService get() = EditorContextService.getInstance(project)
    private val manager get() = FileEditorManager.getInstance(project)
    private var savedAttach = true
    private lateinit var savedSchedule: (Runnable) -> Unit

    override fun setUp() {
        super.setUp()
        PromptHistory.getInstance(project).clear()
        chats.closeAll()
        manager.openFiles.forEach(manager::closeFile) // no editor, no chip, unless a test opens one
        savedAttach = settings.attachEditorContext
        settings.attachEditorContext = true
        savedSchedule = contextService.schedule
        contextService.schedule = { it.run() }
        chats.connector = { FakeConnection().also { connections += it } }
        chat = chats.open(ChatSpec.inProject(root))
        bridge = ChatBridge(project, chat, { newChatRequests++ }) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try {
            bridge.dispose()
            chat.detach(bridge)
            chats.closeAll()
            manager.openFiles.forEach(manager::closeFile)
            settings.attachEditorContext = savedAttach
            contextService.schedule = savedSchedule
        } finally {
            super.tearDown()
        }
    }
```

Append before the final closing `}` of `ChatBridgeTest`:
```kotlin

    fun testReadyPostsTheEditorContextChip() {
        myFixture.configureByText("calc.js", "function total(a) {\n  return a<caret> + 1;\n}\n")
        bridge.handle("""{"type":"ready"}""")
        val msg = posted.last()
        assertEquals("context", msg.str("type"))
        assertEquals("calc.js › total · L2", msg.str("text"))
        assertEquals(false, msg.bool("off"))
    }

    fun testContextOffMutesTheChipAndSkipsOneMessage() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"contextOff","off":true}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        assertEquals(true, posted.last().bool("off"))
        bridge.handle("""{"type":"send","text":"hello"}""")
        val sent = connections[0].userMessages().single()
        assertTrue(sent.endsWith("hello"))
        assertFalse(sent, sent.contains("The user is looking at"))
        assertEquals(false, posted.last { it.str("type") == "context" }.bool("off"))
    }

    fun testContextOffCanBeUndone() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"contextOff","off":true}""")
        bridge.handle("""{"type":"contextOff","off":false}""")
        assertEquals(false, posted.last().bool("off"))
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertTrue(connections[0].userMessages().single().contains("[IDE] The user is looking at a.txt, caret at line 1:1."))
    }

    fun testCaretMovesUpdateTheChipOnlyWhenItsTextChanges() {
        myFixture.configureByText("a.txt", "one\ntwo\n")
        bridge.handle("""{"type":"ready"}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        myFixture.editor.caretModel.moveToOffset(5)
        assertEquals("a.txt · L2", posted.last().str("text"))
        val count = posted.size
        myFixture.editor.caretModel.moveToOffset(6) // same line, same chip
        assertEquals(count, posted.size)
    }

    fun testSettingOffHidesTheChip() {
        myFixture.configureByText("a.txt", "x")
        bridge.handle("""{"type":"ready"}""")
        assertEquals("a.txt · L1", posted.last().str("text"))
        settings.attachEditorContext = false
        chat.refreshContext()
        assertEquals("context", posted.last().str("type"))
        assertEquals("", posted.last().str("text"))
    }
```

Append before the final closing `}` of `src/test/kotlin/claudechat/ui/WebAssetsTest.kt`:
```kotlin

    @Test fun pageShowsTheContextChipAsPlainText() {
        val page = WebAssets.page()
        assertTrue(page.contains("<div id=\"context\" hidden><span id=\"context-text\"></span>"))
        assertTrue(page.contains("case 'context': setContext(msg.text, msg.off); break;"))
        assertTrue(page.contains("contextText.textContent ="))
        assertFalse(page.contains("contextText.innerHTML"))
        assertFalse(page.contains("contextEl.innerHTML"))
        assertTrue(page.contains("send({ type: 'contextOff', off: true })"))
        assertTrue(page.contains("send({ type: 'contextOff', off: false })"))
        assertTrue(page.contains("#context[hidden] { display: none; }"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.ui.ChatBridgeTest' --tests 'claudechat.ui.WebAssetsTest'`
Expected: FAIL — `e: …/ChatBridgeTest.kt… Unresolved reference 'refreshContext'.`, `BUILD FAILED`.

- [ ] **Step 3: Write the Kotlin implementation**

In `src/main/kotlin/claudechat/session/Chat.kt` replace (in `SessionView`):
```kotlin
    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}
}
```
with:
```kotlin
    /** What the IDE window shows for this chat (`IDE: <checkout>`); empty hides it. */
    fun checkout(text: String) {}

    /** The editor-context chip: empty [text] hides it; [off] shows it muted (left out of the next message). */
    fun context(text: String, off: Boolean) {}
}
```

Replace `attach`:
```kotlin
    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
    }
```
with:
```kotlin
    /** EDT only (the chip reads the editor). */
    fun attach(view: SessionView) {
        this.view = view
        view.reset(synchronized(lock) { items.values.toList() })
        view.state(session.state, session.sessionId, session.mode)
        checkoutText.takeIf { it.isNotEmpty() }?.let(view::checkout)
        synchronized(lock) { pendingInsert.also { pendingInsert = null } }?.let(view::insert)
        shownContext = HIDDEN // a new page shows no chip yet
        refreshContext()
    }
```

Replace `skipContext` (from Task 3):
```kotlin
    /** Leaves the editor context out of the next message ([off]) or puts it back. EDT only. */
    fun skipContext(off: Boolean) {
        contextOff = off
    }
```
with:
```kotlin
    /** Leaves the editor context out of the next message ([off]) or puts it back. EDT only. */
    fun skipContext(off: Boolean) {
        contextOff = off
        refreshContext()
    }

    /** The chip text and muted state the page shows. EDT only. */
    private var shownContext = HIDDEN

    /** Recomputes the chip and tells the page when its text or muted state changed. EDT only. */
    fun refreshContext() {
        val next = editorContext(this)?.let(ContextNotes::chip).orEmpty() to contextOff
        if (next == shownContext) return
        shownContext = next
        view?.context(next.first, next.second)
    }
```

In `send` replace:
```kotlin
        contextOff = false
        session.send(text, listOfNotNull(edits, context).joinToString("\n\n").ifEmpty { null })
    }
```
with:
```kotlin
        contextOff = false
        session.send(text, listOfNotNull(edits, context).joinToString("\n\n").ifEmpty { null })
        refreshContext() // the chip is live again
    }
```

Replace the end of the class:
```kotlin
    fun dispose() = session.dispose()
}
```
with:
```kotlin
    fun dispose() = session.dispose()

    private companion object {
        val HIDDEN = "" to false
    }
}
```

In `src/main/kotlin/claudechat/session/Chats.kt` replace:
```kotlin
    private val context = EditorContextService.getInstance(project) // also starts tracking the focused editor
```
with:
```kotlin
    private val context = EditorContextService.getInstance(project) // also starts tracking the focused editor

    init {
        context.addListener(this) { all().forEach(Chat::refreshContext) }
    }
```

In `src/main/kotlin/claudechat/ui/ChatBridge.kt` replace line 4:
```kotlin
import claudechat.cli.arr
```
with:
```kotlin
import claudechat.cli.arr
import claudechat.cli.bool
```
Replace line 66:
```kotlin
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
```
with:
```kotlin
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
            "contextOff" -> chat.skipContext(msg.bool("off") ?: true)
```
Replace lines 98-101:
```kotlin
    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())
```
with:
```kotlin
    override fun checkout(text: String) = post(buildJsonObject {
        put("type", "checkout")
        put("text", text)
    }.toString())

    override fun context(text: String, off: Boolean) = post(buildJsonObject {
        put("type", "context")
        put("text", text)
        put("off", off)
    }.toString())
```

In `src/main/kotlin/claudechat/ui/ChatTabs.kt` replace line 33:
```kotlin
        chat.onCwdChanged = { ApplicationManager.getApplication().invokeLater({ follower.refresh() }, project.disposed) }
```
with:
```kotlin
        chat.onCwdChanged = {
            ApplicationManager.getApplication().invokeLater({ follower.refresh(); chat.refreshContext() }, project.disposed)
        }
```

In `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt` replace lines 3-5:
```kotlin
import claudechat.cli.PermissionMode
import claudechat.follow.CheckoutFollower
import com.intellij.openapi.components.serviceIfCreated
```
with:
```kotlin
import claudechat.cli.PermissionMode
import claudechat.follow.CheckoutFollower
import claudechat.session.Chat
import claudechat.session.Chats
import com.intellij.openapi.components.serviceIfCreated
```
and replace:
```kotlin
        // Only projects whose chat tool window is open have a follower.
        ProjectManager.getInstance().openProjects.forEach { it.serviceIfCreated<CheckoutFollower>()?.refresh() }
```
with:
```kotlin
        // Only projects whose chat tool window is open have a follower and chats.
        ProjectManager.getInstance().openProjects.forEach { project ->
            project.serviceIfCreated<CheckoutFollower>()?.refresh()
            project.serviceIfCreated<Chats>()?.all()?.forEach(Chat::refreshContext)
        }
```

- [ ] **Step 4: Write the page implementation**

In `src/main/resources/webview/index.html` replace line 22:
```html
<main id="log"></main>
```
with:
```html
<main id="log"></main>
<div id="context" hidden><span id="context-text"></span><button id="context-off" title="Leave the editor context out of the next message">×</button></div>
```

In `src/main/resources/webview/chat.js` replace line 11:
```js
  const checkoutEl = document.getElementById('checkout');
```
with:
```js
  const checkoutEl = document.getElementById('checkout');
  const contextEl = document.getElementById('context');
  const contextText = document.getElementById('context-text');
  const contextOffBtn = document.getElementById('context-off');
```
Replace line 55:
```js
        case 'checkout': setCheckout(msg.text); break;
```
with:
```js
        case 'checkout': setCheckout(msg.text); break;
        case 'context': setContext(msg.text, msg.off); break;
```
Replace the `setCheckout` function (lines 173-177):
```js
  // Plain text only: the IDE's checkout name and folder never become markup.
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }
```
with:
```js
  // Plain text only: the IDE's checkout name and folder never become markup.
  function setCheckout(text) {
    checkoutEl.textContent = typeof text === 'string' ? text : '';
    checkoutEl.hidden = !checkoutEl.textContent;
  }

  // Plain text only: file and symbol names from the editor never become markup.
  function setContext(text, off) {
    const t = typeof text === 'string' ? text : '';
    const muted = off === true;
    contextEl.hidden = !t;
    contextEl.classList.toggle('off', muted);
    contextEl.title = muted ? '' : t;
    contextText.textContent = muted ? 'context off for next message — click to restore' : t;
    contextOffBtn.hidden = muted;
  }
```
Replace line 254:
```js
  modeSel.addEventListener('change', () => send({ type: 'setMode', mode: modeSel.value }));
```
with:
```js
  modeSel.addEventListener('change', () => send({ type: 'setMode', mode: modeSel.value }));
  contextOffBtn.addEventListener('click', (e) => { e.stopPropagation(); send({ type: 'contextOff', off: true }); });
  contextEl.addEventListener('click', () => { if (contextEl.classList.contains('off')) send({ type: 'contextOff', off: false }); });
```

Append to `src/main/resources/webview/chat.css`:
```css
#context { display: flex; align-items: center; gap: 4px; align-self: flex-start; max-width: calc(100% - 16px); margin: 6px 8px 0; padding: 1px 2px 1px 8px; border: 1px solid var(--border); border-radius: 10px; color: var(--muted); font-size: .9em; }
#context[hidden] { display: none; }
#context-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
#context.off { cursor: pointer; font-style: italic; opacity: .75; padding-right: 8px; }
#context-off { background: none; border: none; color: var(--muted); padding: 0 4px; line-height: 1; }
#context-off:hover { color: var(--fg); }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gw test --tests 'claudechat.ui.ChatBridgeTest' --tests 'claudechat.ui.WebAssetsTest' --tests 'claudechat.session.ChatsTest'`
Expected: PASS — `BUILD SUCCESSFUL`; ChatBridgeTest `tests="17"`, WebAssetsTest `tests="6"`, ChatsTest `tests="21"`, all 0 failures.

- [ ] **Step 6: Run the whole suite and build the plugin**

Run: `./gw test buildPlugin`
Expected: `BUILD SUCCESSFUL`; 281 tests, 0 failures (236 before this plan + 18 + 14 + 7 + 6); `build/distributions/claude-chat-0.1.0.zip` exists.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/claudechat/session/Chat.kt src/main/kotlin/claudechat/session/Chats.kt \
  src/main/kotlin/claudechat/ui/ChatBridge.kt src/main/kotlin/claudechat/ui/ChatTabs.kt \
  src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt \
  src/main/resources/webview/index.html src/main/resources/webview/chat.js src/main/resources/webview/chat.css \
  src/test/kotlin/claudechat/ui/ChatBridgeTest.kt src/test/kotlin/claudechat/ui/WebAssetsTest.kt
git commit -m "Show the editor context in a chip above the input"
```

---

### Task 5: End-to-end check in a sandbox WebStorm

**Files:** none (fix-ups only if a check fails; each fix gets a regression test where the logic is testable outside the UI).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Prepare a scratch project and launch the sandbox**

```bash
SCRATCH=$(mktemp -d)/v4-check && mkdir -p "$SCRATCH/src/billing" && cd "$SCRATCH"
git init -q
cat > src/billing/invoice.ts <<'EOF'
export interface Item { price: number; qty: number }

export function calculateTotals(items: Item[]): number {
  let total = 0;
  for (const item of items) {
    total += item.price * item.qty;
  }
  return total;
}

export class Invoice {
  constructor(private items: Item[]) {}

  get total(): number {
    return calculateTotals(this.items);
  }
}

export const config = { currency: "EUR" };
EOF
printf 'one\ntwo\nthree\n' > notes.txt
git add . && git commit -qm init
printf 'export const onlyInMain = 1;\n' > src/main-only.ts
echo "$SCRATCH"
cd /Users/atli/projects/plugin && ./gw runIde
```
In the sandbox WebStorm open the printed folder and the Claude Chat tool window. The newest transcript is `ls -t ~/.claude/projects/*/*.jsonl | head -1`; use `grep -o '\[IDE\][^"]*' "$(ls -t ~/.claude/projects/*/*.jsonl | head -1)" | tail -3` to see what the last message carried.

- [ ] **Step 2: Verify each requirement and note the result**

1. Settings > Tools > Claude Chat shows a checked **Attach editor context to messages** box.
2. Open `src/billing/invoice.ts`, put the caret inside `calculateTotals` on line 6. The chip above the input reads `invoice.ts › calculateTotals · L6` and updates shortly (about 150 ms) after the caret moves; on line 15 it reads `invoice.ts › total · L15`; on line 19 inside `config` it shows `config` (or `currency`); on line 1 inside the interface it shows `Item`. Select lines 4–7: the chip reads `invoice.ts › calculateTotals · L4–7`.
3. Click into the chat input: the chip keeps showing the editor's context (focus in the chat keeps the last editor).
4. With the caret on line 6 and nothing selected, send "what does this function do?". The user bubble shows only that text; the transcript grep shows ``[IDE] The user is looking at src/billing/invoice.ts, caret at line 6:…, inside function `calculateTotals` (lines 3–9).``; Claude's answer is about `calculateTotals` without asking which file.
5. Type a character in `invoice.ts` without saving and send "and now?": the transcript shows the edit note paragraph first, then the context paragraph, then the text; the file is saved on disk.
6. Click ×: the chip turns muted and reads `context off for next message — click to restore`. Send "hi": no context paragraph in the transcript; the chip is live again. Click × and then the muted chip: it is live again and the next message carries the context.
7. Open `notes.txt`, caret on line 3: chip `notes.txt · L3`, note without `inside …`.
8. Open an image or any non-text file tab: the chip hides. Open a diff (Git > Show History, double-click a change) and click into it: the chip does not switch to the diff.
9. Open a file outside the project (File > Open… any file under `/tmp`): the chip hides.
10. Untick **IDE follows the selected chat's checkout**. New chat > **In a new worktree…** `ctx-1`, send "Run pwd." and wait. With `src/billing/invoice.ts` (main) in the editor and the `ctx-1` tab selected, the chip shows `invoice.ts › …`, and a message from that tab carries `src/billing/invoice.ts` (the counterpart path). With `src/main-only.ts` in the editor (untracked, so the worktree has no counterpart) the `ctx-1` tab shows no chip; the `main` tab shows `main-only.ts · L1`. Tick the follow setting again.
11. Untick **Attach editor context to messages** and Apply: every tab's chip disappears at once and messages carry no context paragraph. Tick it and Apply: the chips come back without moving the caret.
12. Security spot checks: rename `notes.txt` to `<b>x</b>.txt` and open it: the chip shows the name literally (no bold). Drag a file onto the chat page: it does not navigate. Ask Claude for a markdown link to https://example.com and click it: it opens in the external browser only.

- [ ] **Step 3: Clean up the scratch worktree**

```bash
cd <scratch>
git worktree remove --force .claude/worktrees/ctx-1
git branch -D worktree-ctx-1
```

- [ ] **Step 4: Fix any failures**

For each failing check, find the root cause (use superpowers:systematic-debugging), add a failing test when the cause is in non-UI code (`ContextNotes`, `Symbols`, `ContextPaths`, `SymbolFinder`, `EditorContextService`, `Chat`), fix, rerun `./gw test`, and commit with a message describing the fix.

- [ ] **Step 5: Final state**

```bash
git status --short   # expect clean
```

---

## Decisions on points the spec leaves open

| Point | Decision |
|---|---|
| Symbol name "as presented by the IDE" when Structure View labels carry signatures (`calc(n: number): number`) | `PsiNamedElement.name`, else the label cut at `(` (`Symbols.nameFromLabel`). |
| Structure model vs breadcrumbs | Structure View model (`TreeBasedStructureViewBuilder.createStructureViewModel(null)`), walked by PSI ranges; kind word from `ElementDescriptionUtil` + `UsageViewTypeLocation`. |
| "Function-like" | Kind word contains function/method/constructor/lambda/closure, or the PSI class name contains Function/Method/Lambda/Constructor (`Symbols.isFunctionLike`). |
| Note wording for `symbol` + `container` | ``inside <kind> `name` (lines a–b), within <kind> `container` (lines c–d)``. |
| Single-line ranges | `line N` in the note, `L N` in the chip (instead of `lines N–N`). |
| Selection ending at column 0 of the next line | Ends on the previous line (same rule as `AddToChatAction`). |
| Column | 1-based character offset in the line (tabs count as one), not the visual column. |
| Chip symbol when there is a container | The innermost symbol's name (the spec's `<symbol name>`); the container appears only in the note. |
| Main chat looking at a worktree file with no counterpart in main | No context (main does not own `.claude/worktrees`, as v1 edit notes and v3 `counterpart` already treat it). |
| "Has a counterpart" | The counterpart file exists (VFS lookup without refresh), as in v3's editor swap. |
| Focus in a non-text editor (diff) | The last text editor stays the context editor; a non-text **file tab** selected clears it (no context). |
| Who owns the skip-once state | `Chat` (Kotlin), so `send` and the flag cannot race; the page only sends `contextOff`. |
| Debounce | One 150 ms `Alarm` per project for all chats; each chat posts only when its `(text, off)` changed. |

## Spec coverage

| Spec requirement | Task |
|---|---|
| Context editor: focused text editor, else last focused in the project; local files only | 2 (`EditorContextService.editorFocused`, `contextEditor`, `accepts`) |
| `path` relative to the chat's cwd; counterpart in the chat's checkout; else no context | 1 (`ContextPaths.forChat`, `CheckoutMapper.checkoutOf`), 2 (`contextFor`) |
| `caret` 1-based line:column of the primary caret; `selection` 1-based lines | 2 (`locate`) |
| `symbol` innermost structural element, name and lines; function-like preference with `container` | 1 (`Symbols.pick`, `isFunctionLike`), 2 (`SymbolFinder`) |
| Note text, kind word, omissions, one paragraph | 1 (`ContextNotes.note`) |
| Note after the edit note, before the text, not in the bubble | 3 (`Chat.send`, `testEditNoteComesBeforeEditorContext`) |
| Location only; unsaved changes saved first | 1 (note has no contents), 3 (edit note saves before the context is read) |
| Chip text `<file> › <symbol> · L<line>` / `· L140–145`, × button | 1 (`ContextNotes.chip`), 4 (page) |
| Live updates, debounced ~150 ms | 2 (listeners + `Alarm`), 4 (`Chats` → `Chat.refreshContext`) |
| × skips next message only, muted "click to restore", live again after send | 3 (`skipContext`), 4 (bridge `contextOff`, page) |
| Hidden without context; textContent only; v1 security unchanged | 4 (`setContext`, `WebAssetsTest`) |
| Setting "Attach editor context to messages", default on; off → no chip, no note | 3 (setting, provider gate), 4 (`apply()` refresh) |
| Out of scope: code contents, extra carets, non-text editors | 1 (note), 2 (`primaryCaret`, editor kinds) |
