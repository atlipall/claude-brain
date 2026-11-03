# Claude Chat for WebStorm

A personal JetBrains plugin (built for WebStorm 2026.2) that gives the
[Claude Code](https://docs.claude.com/en/docs/claude-code) CLI a native chat panel inside the IDE.

It runs your locally installed `claude` binary, so it uses whatever login that CLI has (e.g. your
Claude Pro subscription). The plugin never reads, stores or forwards credentials.

## Features

- **Native chat panel.** A "Claude Chat" tool window with streaming replies, markdown, tool calls,
  and permission prompts (Allow / Allow for session / Deny with a reason). Stop, restart and
  permission mode (Ask before edits / Accept edits / Plan / Auto) are in the header.
- **Several chats at once.** One tab per chat, each with its own `claude` process. The `+` button
  (or "New chat") offers: in this project, in a new git worktree (`claude --worktree <name>`), in an
  existing worktree, or resume a past session (across the project and its worktrees).
- **The IDE follows the selected chat.** Selecting a worktree chat switches the Project view to that
  checkout's scope, swaps open editors to the same files in that checkout (caret kept), and defaults
  Find in Files to it. The chat header shows `IDE: main` / `IDE: <worktree>`.
- **Files are clickable.** Paths Claude reads, edits or mentions (in backticks or markdown links)
  open in the editor; edits have a "View diff" link.
- **Claude knows what you edited.** Files you save between messages are listed in a hidden note
  on your next message, so Claude re-reads them instead of working from stale contents.
- **Claude knows where you are looking.** Every message carries your current file, caret line,
  selection and enclosing function, so "what does this function do?" just works.
- **Images.** Paste, drop or 📎-pick images to send with a message (PNG/JPEG/GIF/WebP, up to 10,
  5 MB each, large ones downscaled). Images Claude reads, and local project images referenced in its
  replies, are shown inline. Remote images are never loaded.
- **Up/Down recall** of previously sent messages, per project, across restarts.
- **Add to Claude Chat** (Cmd+Shift+Option+K, or the editor context menu) inserts `@path#Lx-y`
  for the current selection.

## Requirements

- WebStorm 2026.2 (build 262) — other 2026.2 JetBrains IDEs with JCEF should also work.
- [Claude Code](https://docs.claude.com/en/docs/claude-code) installed and logged in
  (run `claude` once in a terminal). The plugin looks for `claude` on your PATH, then in
  `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin`; you can also set the path in settings.

## Install

Quit WebStorm, then from the project root:

```sh
./install.sh            # builds and installs into the newest WebStorm config folder
./install.sh --no-build # install the existing zip
./install.sh --force    # install while WebStorm runs; restart it afterwards
```

Or manually, after `./gw buildPlugin`, either:

- Settings → Plugins → ⚙️ → **Install Plugin from Disk…** → `build/distributions/claude-chat-0.1.0.zip`, and restart; or
- with WebStorm closed, unzip it into the plugins folder:
  ```sh
  unzip -o build/distributions/claude-chat-0.1.0.zip \
    -d "$HOME/Library/Application Support/JetBrains/WebStorm2026.2/plugins"
  ```

### Worktree indexing

WebStorm excludes `.claude/worktrees` from indexing by default. The first time you select a
worktree chat, the plugin offers **Include and restart** to change that (it never changes the setting
without your click). Until then, editors still follow the worktree but without code insight, and the
Project view / Find scope are left alone.

## Settings

Settings → Tools → **Claude Chat**:

| Setting | Default |
|---|---|
| Claude binary | auto-detect |
| Default permission mode | the CLI's own setting |
| Extra CLI arguments (e.g. `--model sonnet`) | none |
| IDE follows the selected chat's checkout | on |
| Attach editor context to messages | on |

## Development

There is no system Java needed: `./gw` runs Gradle with WebStorm's bundled JBR
(`/Applications/WebStorm.app/Contents/jbr`).

```sh
./gw test          # unit + platform tests
./gw buildPlugin   # build/distributions/claude-chat-0.1.0.zip
./gw runIde        # sandbox WebStorm with the plugin loaded
```

Stack: Kotlin, IntelliJ Platform Gradle Plugin 2.x, JCEF for the chat page (plain JS with vendored
`marked` and `DOMPurify`). The plugin talks to the CLI with
`claude -p --input-format stream-json --output-format stream-json --permission-prompt-tool stdio`.

Layout:

```
src/main/kotlin/claudechat/
  cli/        CLI protocol, process transport, command line
  session/    chats, sessions, transcripts, prompt history
  context/    editor context (file, caret, enclosing symbol)
  follow/     IDE follows the selected chat's checkout
  git/        worktree discovery and naming
  image/      image validation and scaling
  ide/        edit tracking, file links, editor actions
  ui/         tool window, tabs, JCEF bridge, popups
  settings/   settings and configurable
src/main/resources/webview/   chat page (HTML/CSS/JS)
docs/superpowers/             design specs and implementation plans
```

## Security notes

The chat page renders model output, so it is treated as untrusted: a Content Security Policy allows
only inline scripts/styles and `data:` images supplied by the plugin; markdown is sanitized with
DOMPurify (no images, forms, styles); only page-created elements can open files, answer permission
prompts or show images; the page can't navigate away; only http(s) links open in the browser.
`ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` are removed from the CLI's environment so it always uses
your CLI login.

## Status

Personal project, not published to the JetBrains Marketplace.
