# Claude Chat — IDE follows the selected chat's checkout (v3)

Date: 2026-11-03
Status: Approved in chat (option "A")
Extends: v1 and v2 specs. Everything there still applies unless changed here.

## Goal

When the user selects a chat tab whose folder is a different checkout (a git worktree, typically `<project>/.claude/worktrees/<name>`), the IDE window "follows" it so the user reads and edits the same files Claude is working on. An IntelliJ project's root cannot change, so "following" means scoping and swapping within the one window.

## Behaviour

### Checkouts
- A chat's checkout is its `cwd` (v2: predicted worktree folder until `system/init`, then the reported one).
- The main checkout is the project base path. A chat whose cwd is the project dir (canonical comparison) is "main".
- Only checkouts under the project dir are followable (worktrees created via `claude --worktree`). A chat in a worktree outside the project dir does not switch anything; the chat header shows a note that the checkout is outside this window and suggests opening it as a separate project.

### On selecting a chat tab (and when a selected chat's cwd changes, e.g. on `system/init`)
1. **Project view scope.** Switch the Project tool window to a named scope for that checkout:
   - `Claude: main` — the project, excluding `.claude/worktrees/**`.
   - `Claude: <name>` — `.claude/worktrees/<name>/**` only.
   Scopes are created/updated by the plugin as named scopes (local, not shared). Switch only if the Project view is currently showing one of the plugin's `Claude:` scopes or the default "Project" pane — if the user picked some other view/scope themselves, leave it alone.
2. **Swap open editors.** For each open editor whose file belongs to the previous checkout and has a counterpart at the same relative path in the new checkout, replace it with the counterpart in the same position, keeping caret line/column (clamped) and which editor was selected. Files with no counterpart, and files outside both checkouts, stay open untouched. Unsaved changes are never lost (IntelliJ keeps document changes when an editor closes; additionally save the document before swapping).
3. **Search scope default.** Find in Files and Search Everywhere default to the checkout's scope where the platform allows setting a default; otherwise the named scope is at least available in their scope dropdowns. (Implementation must use public API; if no public API exists for setting the default, document it and only provide the scopes.)
4. **Indicator.** The chat page header shows `IDE: main` or `IDE: <name>` for the selected chat.

### Toggle
- A setting "IDE follows the selected chat's checkout" (default on), in Settings > Tools > Claude Chat.
- Switching back to a main chat restores the `Claude: main` scope and swaps editors back.

## Not changed
- `.claude/worktrees` is NOT excluded from indexing (excluded files lose code intelligence).
- Git widget, Commit tool window, run configurations and the terminal stay project-wide.

## Out of scope
- Opening worktrees as separate IDE windows (option B).
- Terminal tabs per worktree.
