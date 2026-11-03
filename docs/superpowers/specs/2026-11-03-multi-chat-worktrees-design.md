# Claude Chat — Multiple chats, worktrees, message recall (v2)

Date: 2026-11-03
Status: Approved in chat
Extends: `2026-11-03-claude-chat-webstorm-design.md` (v1). Everything in v1 still applies unless changed here. This replaces the v1 non-goal "Multiple concurrent chats visible side by side".

## Goal

Work on several things at once: run multiple Claude chats in parallel, each optionally in its own git worktree, and recall previously sent messages with the arrow keys instead of the History dropdown.

## Verified CLI behaviour (claude 2.1.317, probed)

- `claude -w <name>` / `--worktree <name>` creates a git worktree at `<project>/.claude/worktrees/<name>` on a new branch `worktree-<name>` and runs the session there. The `system/init` message's `cwd` is the worktree path.
- Sessions are stored per cwd, so a worktree session's transcript lives under `~/.claude/projects/<encoded worktree path>/<id>.jsonl`.

## UI

### Tabs
- The Claude Chat tool window shows one native content tab per chat (IntelliJ `ContentManager`, closeable tabs). Each tab owns its own JCEF page, `ChatSession` and `claude` process.
- Tab title: worktree name or `main`, plus `·` and the first ~30 characters of the first user message once sent.
- Closing a tab disposes its session (stops the process). The transcript remains on disk and can be resumed.
- When the tool window first opens with no tabs, one `main` chat is created automatically (same behaviour as v1).

### New chat
A `+` action in the tool window title bar, and the existing page "New chat" button, open a popup (IDE `JBPopupFactory` list):
1. **In this project** — cwd = project base path, no worktree.
2. **In a new worktree…** — prompts for a name (default: `wt-<n>`; must match `[A-Za-z0-9._-]+`, not an existing worktree name); starts `claude --worktree <name>` with cwd = project base path. The chat's folder becomes the `cwd` reported by `system/init`.
3. **One entry per existing worktree** from `git worktree list --porcelain` (excluding the main one) — cwd = that worktree path.
4. **Resume a past session…** — a searchable popup list of past sessions across the project and every known worktree path (from `git worktree list` plus `<project>/.claude/worktrees/*`), newest first, showing title, folder label and time. Opens in a new tab with cwd = the session's folder and `--resume <id>`.

If `git` is unavailable or the project is not a git repo, options 2 and 3 are omitted.

The v1 History dropdown is removed from the page.

### Up/Down message recall
- In the message box, ArrowUp when the caret is on the first line (and no IME composition) replaces the input with the previous sent message; ArrowDown on the last line moves forward, ending at the draft the user had before starting recall.
- History is per project, shared by all tabs, most recent last, de-duplicated against the immediately previous entry, capped at 100, persisted across IDE restarts (project-level persistent state).
- Sending a message appends it to history.

## Per-chat folder

Each chat has a `cwd` (absolute path). It starts as the launch directory and is updated from `system/init` (important for `--worktree`).

- **File links** (`resolvePaths`, `openFile`): resolve relative paths against the chat's cwd first, then project content roots. A file resolves if it is inside project content OR under the chat's cwd. Files Claude reports with absolute paths open as-is.
- **Edit notes**: `EditTracker` records saved files by absolute path. When a chat sends, it receives only files under its own cwd. For the `main` chat (cwd = project root), files under `<project>/.claude/worktrees/` are excluded. A file is reported to each chat whose folder contains it, once per chat; recording is per chat, not global (consuming for one chat does not clear it for another).
- **Add to Claude Chat** inserts into the selected tab's chat.

## Out of scope (v2)

- Removing or merging worktrees from the plugin.
- Excluding `.claude/worktrees` from IDE indexing.
- Per-tab permission-mode defaults beyond the existing global setting.

## Carried-over item from v1 review

The v1 fix wave added a navigation guard in `ChatPanel` (`onBeforeBrowse` + initial-URL capture). The user has since seen the page render (the History dropdown was visible), so the initial load is not cancelled; whether the bridge is injected is still unconfirmed. The v2 ChatPanel refactor must keep: bridge injected only on the chat page, and navigation away from it blocked, without ever blocking the initial load.
