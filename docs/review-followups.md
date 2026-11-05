# Review follow-ups

Open items from the whole-project review of 2026-11-05 (branch `fable-review`). Low-risk findings were fixed in that
branch; these need a design decision or a larger change. Line numbers are as of that branch and may drift; function
names are the stable reference.

## Threading and performance

### 1. Process spawn and shell-environment lookup run on the EDT — done
Done: `ClaudeProcess.start` returns at once; the shell environment, the binary lookup and the spawn run on its own launch thread, and lines sent meanwhile are queued and written once claude is up. A failed launch arrives as an exit with `CliConnection.LAUNCH_FAILED` and the reason, shown as the notice. `ChatSession` and its tests are unchanged.
- Where: `session/Chats.kt` `open` → `chat.start()` and `defaultConnect`; `session/ChatSession.kt` `start()` holds `lock`
  while calling `connect`; also reached from `Chat.revertTo`/`rewindTo` and `ChatBridge` restart.
- Problem: `defaultConnect` calls `EnvironmentUtil.getEnvironmentMap()` (blocks until the IDE's shell environment has
  loaded, which can take seconds right after startup, exactly when restored chats open) and `ProcessBuilder.start()`,
  both on the EDT and under the session lock, so stdout lines arriving meanwhile wait too.
- Options: run `connect` off the EDT and take the lock only to install the connection (guarded by `generation`); or, at
  minimum, resolve the environment once on a pooled thread when `Chats` is created.
- Watch out: tests rely on the current synchronous start/rewind ordering.

### 2. Whole-checkout VFS refresh before every message and revert — done (message side)
Done: before each label, `mark` refreshes only the files the native file watcher has marked dirty. It falls back to the full rescan when the chat was idle for less than a second before the message (a queued message goes out the moment a turn ends, before the watcher has reported its last writes), or when the watcher isn't running, is still being given its roots, or can't watch a folder in or around the checkout. This uses the platform's internal `FileWatcher` API, so the plugin verifier will warn. Revert still does the full refresh, on purpose: it's rare, user-started, and must be complete. The fast path can't be covered by tests (the test VFS has no running watcher), so it was checked by reading the code only.
- Where: `ide/TurnCheckpoints.kt` `mark` and the revert path, `VfsUtil.markDirtyAndRefresh(false, true, true, dir)`.
- Problem: re-lists every folder under the checkout (for a main chat that includes `.claude/worktrees` and
  `node_modules`) on every outgoing message. On a big repo that is seconds of IO and a burst of VFS events on the EDT,
  and it makes "checkpoint isn't ready yet" more likely.
- Options: refresh only the files the previous turn's edit/Bash hooks recorded (`refreshIoFiles`); or
  `reloadChildren = false`; or skip excluded roots.

### 3. Quick-fix availability runs on the EDT; ModCommand fixes may need UI — partly done
Done: `apply` computes a ModCommand fix's command first and refuses one that would show a chooser or a conflicts dialog, with a message telling Claude to make the edit by hand, instead of reporting "made no change". The KDoc is updated.
Still open, tracked in https://github.com/atlipall/tether/issues/7: availability still runs on the EDT. Alt+Enter judges a fix with the caret at the problem, and the caret can only be moved on the EDT; checking off the EDT needs per-offset round trips and confirmation that `ShowIntentionActionsHandler.availableFor` is safe in a background read action. Needs trying in a running IDE.
- Where: `ide/tools/Fixes.kt` `list` (checks `isAvailable` for every candidate inside `invokeLater`) and
  `runsWithoutDialogs`.
- Problems: heavy intentions (TypeScript import resolution) freeze the UI while listing. The KDoc says ModCommand actions
  "can't open a dialog", but a `ModChooseAction` or `ModDisplayMessage` can; on the hidden editor it is shown anchored
  off-screen or silently dropped, and `apply` then reports "made no change".
- Options: compute availability in `ReadAction.nonBlocking` off the EDT, keeping only editor creation/release and the
  invoke on the EDT; leave out (or run with `ModCommandExecutor.executeInBatch`) commands that contain a chooser. Update
  the KDoc either way.

## Behaviour

### 4. Find in Files default scope can be left pointing at a Tether scope — done
Done: the user's own default is kept in `TetherSettings` (`findScopeSaved`, `savedFindScope`), so it survives a crash, and `SearchDefaults` remembers which project set the plugin scope. It is put back when that project stops following or closes (not when some other project does), and when a project's `CheckoutFollower` starts while a scope from a crash is still set. Tests cover the crash and two-project cases.
- Where: `follow/IdeViews.kt` (Find scope save/restore), `follow/CheckoutFollower.kt` `dispose`.
- Problem: `FindSettings.defaultScopeName` is saved application-wide, but the user's own value is kept only in memory
  and restored only when the *last* `CheckoutFollower` is disposed.
  - IDE crash while following a worktree: next launch defaults to "Claude: wt1", which is then treated as Tether's own,
    so the user's original scope is lost for good.
  - Two projects open and the Tether one closes first: the other project keeps a scope that doesn't exist there.
- Options: persist the saved name (e.g. in `TetherSettings`) and restore it when the project that set it closes.

### 5. Theme changes don't reach open chat tabs; the page can't be reloaded — done
Done: `ChatPanel` listens to `LafManagerListener.TOPIC` and `EditorColorsManager.TOPIC` and re-sends the theme. A navigation to the page's own URL (a reload) is allowed. When the renderer process dies, the page is loaded afresh, at most 3 times per tab, and the bridge re-sends everything on `ready`. Not covered by tests (needs JCEF); checked by compiling and reading the code only.
- Where: `ui/ChatBridge.kt` posts `Theme.message()` only on `ready`; `ui/ChatPanel.kt` cancels every main-frame
  navigation once `pageUrl` is set.
- Problem: after switching Light/Dark or the editor colour scheme, open tabs keep the old colours until reopened. A
  JCEF reload (including the one after a renderer crash) is refused, so a crashed tab stays dead.
- Options: subscribe to `LafManagerListener.TOPIC` and `EditorColorsManager.TOPIC` (on the panel's disposable) and
  `post(Theme.message())`; `applyTheme` is already idempotent. Optionally allow a navigation to `pageUrl` itself so a
  reload re-runs `onLoadEnd` and re-injects `__send`.

### 6. Background failures make the New Chat popup silently do nothing — done
Done: `background()` catches a failure in `compute`, logs it and shows an error notification in the "Tether" group. Each New Chat popup step names what failed ("Couldn't list the sessions", …).
- Where: `ui/Background.kt` `background()`; callers in `ui/NewChatPopup.kt` (worktree listing, `store.listAll`,
  `chats.read`).
- Problem: an exception in `compute` ends the task; `show` never runs and nothing is reported, so clicking `+` or Resume
  does nothing.
- Options: catch non-control-flow exceptions in `background`, log them, and show a notification (the "Tether" group
  exists in plugin.xml), or let callers pass an `onError`.

### 7. `FileLinker.open` accepts any local path
- Where: `ide/FileLinker.kt` `open`; only caller is `ChatBridge` `openFile`.
- Problem: unlike `resolve`/`localImage`, `open` doesn't check that the file is project content or under the chat's
  folder. Only page-created links reach it (guarded by `trusted` in chat.js), so this is defence in depth, not a live hole.
- Option: pass the chat's `cwd` and refuse files that `accepts(file, cwd)` rejects.

## UI (from the Apple HIG review)

### 8. Activity strip runs three animations at once — done
Done: removed the text shimmer; the dots and the seconds counter stay.
- Where: `webview/chat.css` `.dots` and `.label.shimmer`, `webview/chat.js` seconds counter.
- HIG motion: add motion only where it carries meaning; avoid it on interactions that happen often. Reduced motion is
  already honoured.
- Suggestion: keep either the dots or the shimmer, not both.

### 9. Interactive elements inside `role=button` tool rows — done
Done: tool rows and agent heads are plain elements marked `expandable` (a click anywhere still toggles); the `.tool-toggle` arrow is the `role=button` with `aria-expanded` and an "Details of <tool>" label, and Enter/Space on it toggles.
- Where: `webview/chat.js` `renderToolRow` (a compact row is `role=button` and contains an `<a class="file">` and the
  "View diff" button).
- Problem: ARIA forbids interactive descendants of a button, and VoiceOver may collapse them (the keyboard still works).
- Fix: move `role=button`/`tabindex` to the `.tool-toggle` span and keep the row's click handler. This changes the
  keydown handling and a string matched in `WebAssetsTest`.

### 10. Wording — done
Done: the header button is "View changes" (README updated), "Review all" is "View all changes", a failed turn without text says "This turn failed.", and effort options read "High effort".
- "Diff" (header) vs "View diff" vs "Review all" for the same idea; consider "Show changes" for the header (README
  needs the same change).
- The `'Error'` fallback on a failed turn is a bare word; e.g. "This turn failed".
- Effort options read "low effort" in lower case next to capitalised model names; capitalise the level.

### 11. Attach button uses a colour emoji — done
Done: inline SVG paperclip in `currentColor` with `aria-label`; `WebAssetsTest` and README updated.
- Where: `webview/index.html` `#attach` (📎).
- Problem: Apple Color Emoji ignores `currentColor`, so the icon doesn't follow the theme, and there is no `aria-label`.
- Fix: inline SVG paperclip with `fill="currentColor"` plus `aria-label="Attach images"`. `WebAssetsTest`
  (`pageSendsImagesToTheIdeInChunks`) matches the exact tag, so update it too.

### 12. Smaller UI polish — done
Done: `prefers-contrast: more` makes muted text full colour and borders 2px; `.agent-body` indents 26px and the strip, queue and attachments use 10px side margins; `Theme.kt` sends the IDE's error colour as `--danger`, and Stop is now an outlined button so its text no longer sits on red.
- `prefers-contrast: more` isn't supported; muted text and 1px borders are the only hierarchy cues. Note `--muted` is
  set inline on `<html>`, so an override needs `!important`.
- Alignment: `.agent-body` indents 18px while `.tool-detail` indents 26px; the log has 10px side padding while
  `#activity`, `#queue` and `#attachments` use 8px.
- In dark themes `--danger` (`#c75450`) is about 3.8:1 on the background, below 4.5:1. A brighter red would make the
  white text on the red Stop button unreadable, so this needs a design choice, e.g. use the IDE's own error colour from
  `Theme.kt`, or make Stop an outlined button.

## Code hygiene

### 13. Inline comments against CLAUDE.md — done
Done: inline comments across `src/main` (Kotlin and `chat.js`) and `src/test` were moved into KDoc/JSDoc, assertion messages, better names or small extracted functions, or deleted when they restated the code. Behaviour is unchanged (full test suite passes; a separate review of the diff found no behaviour change). Kept on purpose: the `//` block above `ScopePatterns` in `follow/Checkouts.kt`, one top-of-rule comment in `chat.css` (CSS has no doc comments), and the `// --- section ---` dividers between tests in a few test classes.
Many function bodies have `//` comments that belong in KDoc or in better names. Heaviest files: `session/ChatSession.kt`,
`session/Chat.kt`, `follow/CheckoutFollower.kt`, `context/EditorContextService.kt`, `ide/TurnCheckpoints.kt`,
`image/Images.kt`, `ui/ChatBridge.kt`, `webview/chat.js`. Keep the one above `ScopePatterns` in `follow/Checkouts.kt`
(`//*` would end a KDoc block). Best done file by file as each one is next edited.

### 14. Small dead code — done
Done: `CheckoutScopes.find` is `internal` with KDoc saying only tests use it; `SessionView.state` no longer takes a session id and the page's `state` message no longer carries one.
- `follow/CheckoutScopes.kt` `find(checkout)` is used only by its test; drop it or make it `internal`.
- `ChatBridge.state()` sends `sessionId`, which `chat.js` `setState` never reads.
