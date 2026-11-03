# Claude Chat for WebStorm — Design

Date: 2026-11-03
Status: Draft, awaiting review
Audience: personal use only (not published to the JetBrains Marketplace)

## Goal

A JetBrains IDE plugin (primary target: WebStorm) that gives a native chat panel for Claude Code, so that:

1. A new Claude session can be started from the IDE with one click.
2. The conversation happens in a native tool-window chat, not a terminal.
3. Files Claude reads, edits or mentions are clickable and open in the editor (edits can open in the diff viewer).
4. Edits the user makes in the IDE between turns are reported to Claude so it does not work from stale contents.
5. Usage runs on the user's existing Claude Pro subscription.

## Non-goals (v1)

- Image or file attachments.
- Multiple concurrent chats visible side by side (one active session per project; others resumable).
- Exposing IDE tools to Claude via a custom MCP server.
- Marketplace distribution. Anthropic does not permit third-party products to offer claude.ai login without approval; this plugin is for personal use and never handles credentials itself.

## Approach

The plugin drives the locally installed `claude` CLI (verified: 2.1.317 at `~/.local/bin/claude`) in headless streaming mode:

```
claude -p --input-format stream-json --output-format stream-json \
       --include-partial-messages --verbose \
       --permission-prompt-tool stdio [--permission-mode <mode>] [--resume <id>]
```

The CLI owns authentication (the user's Pro login), tools, `CLAUDE.md`, MCP servers, settings and session persistence. The plugin owns UI and IDE integration only.

Rejected alternatives:
- **Agent SDK sidecar (Node):** cleaner API, but adds a second process and language, and the SDK spawns the same CLI underneath.
- **JetBrains AI Assistant + ACP adapter:** no plugin to write, but uncertain coverage of the edit-tracking and file-linking requirements.

## Tech stack

- Kotlin, Gradle, IntelliJ Platform Gradle Plugin 2.x, Gradle JDK toolchain (no system Java on the machine; toolchain downloads one).
- Target platform: WebStorm, current release; `sinceBuild` set to that release's branch.
- Chat UI: JCEF browser (`JBCefBrowser`) loading bundled HTML/CSS/JS from plugin resources. Plain JS (or a pre-built Preact bundle checked into resources); no frontend build step in the Gradle build.
- Kotlin ↔ JS bridge: `JBCefJSQuery` for JS→Kotlin, `executeJavaScript` for Kotlin→JS; all messages are JSON objects with a `type` field.

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `ClaudeProcess` | Spawn/kill one CLI process in the project root. Write NDJSON to stdin; read stdout lines and parse into typed `CliEvent`s; capture stderr tail. No UI knowledge. | `claude` binary path (setting, default: resolve from PATH via login shell) |
| `ChatSession` | One conversation: session id, ordered chat items, state (`Idle`, `Running`, `AwaitingPermission`, `Dead`). Maps `CliEvent`s to `ChatItem`s; queues user messages sent while running; answers permission requests. | `ClaudeProcess` |
| `SessionManager` (project service) | Active session, "New chat", list of past sessions for the project, resume via `--resume <id>`. | `ChatSession` |
| `EditTracker` (project service) | Records project files the user changed/saved since the last user turn (document and VFS listeners). Ignores writes performed by the CLI (detected by matching paths from Claude's Edit/Write tool calls within the current turn). Produces a note prepended to the next user message, then resets. | IntelliJ document/VFS events |
| `ChatToolWindow` | Right-side tool window: JCEF chat view, header with New chat, session dropdown, permission-mode dropdown, Stop button. Bridges `ChatSession` state to the web view. | `SessionManager`, JCEF |
| `FileLinker` | Produces links for tool-call `file_path`s and `path` / `path:line` mentions in assistant text (only paths that resolve inside the project). Click → open in editor at line. Edit/Write tool calls get "View diff" → IntelliJ diff viewer, before vs. after. | Editor, `DiffManager` |
| `AddToChatAction` | Editor action (Cmd+Shift+Option+K and context menu) inserting `@relative/path#Lstart-end` for the current selection into the chat input. | `ChatToolWindow` |

### Edit-tracking note format

Prepended to the user's message text as a separate paragraph:

```
[IDE] Since your last turn the user edited these files: src/a.ts, src/b.ts.
Re-read them before relying on earlier contents.
```

Only sent when the list is non-empty.

### Diff "before" contents

When an Edit/Write tool call is seen, `FileLinker` snapshots the file's current on-disk contents before the tool result arrives (the tool-call event precedes execution). For permission-gated edits the snapshot is taken when the permission request arrives. If no snapshot exists, "View diff" is hidden.

## Data flow (one turn)

1. User submits text in the web view → `ChatSession.send(text)`.
2. `EditTracker` note (if any) is prepended; tracker resets.
3. `ClaudeProcess` writes a `user` message line to stdin.
4. stdout events stream in: partial text deltas, `assistant` messages (text, tool_use), `user` messages (tool_result), `control_request` (`can_use_tool`), `system` messages, and finally `result`.
5. Each event becomes a `ChatItem` update pushed to the web view.
6. `result` ends the turn: state → `Idle`, cost/duration shown, next queued message (if any) is sent.
7. Stop button sends an interrupt control request.

## Permissions

- A `can_use_tool` control request renders as a card: tool name, input (diff preview for Edit/Write/MultiEdit, command for Bash), buttons **Allow**, **Allow for session**, **Deny** (with optional reason).
- The answer is written back as a `control_response`.
- Header dropdown sets the permission mode (`manual`, `acceptEdits`, `plan`, `auto`); default is unset (CLI settings decide). Changing it mid-session uses the corresponding control request if supported, otherwise applies at next session.
- **The stream-json and control-protocol message shapes are not trusted from memory.** Plan task 1 is a throwaway probe that records real traffic from the installed CLI (text turn, tool call, permission request, interrupt, resume) into fixture files. Kotlin event types are written against those fixtures, which also serve as test data.

## Errors

| Situation | Behaviour |
|---|---|
| `claude` not found | Panel shows setup instructions and a link to the binary-path setting. |
| Process exits unexpectedly | Session → `Dead`; panel shows stderr tail and "Restart (resume)" which respawns with `--resume <id>`. |
| Not logged in / usage limit | CLI's message shown verbatim. |
| Unknown event type | Logged at debug level and skipped. |
| Malformed JSON line | Logged and skipped; does not kill the session. |

## Testing

- `ClaudeProcess` / `ChatSession`: unit tests replaying recorded fixture streams through a fake process (stdin/stdout pipes). No live Claude calls in tests.
- `EditTracker`, `FileLinker`: `BasePlatformTestCase` tests.
- Web view and end-to-end: manual verification via `./gradlew runIde` in a sandbox WebStorm.

## Settings

- Path to `claude` binary (default: auto-detect).
- Default permission mode (default: unset).
- Extra CLI arguments (free text, for e.g. `--model`).
