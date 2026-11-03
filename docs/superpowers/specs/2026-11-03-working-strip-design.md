# Claude Chat — Working indicator above the input (v6)

Date: 2026-11-03
Status: Approved in chat (mockup option "D")
Extends: v1–v5 specs.

## Problem

While Claude works, the only hint is a small muted "Working…" at the top right of the panel; the user's focus is on the message box. Opus can think for many seconds before any text streams, and thinking events are currently ignored, so nothing visible changes.

## Design

### Status strip (above the message box)
- A single-line strip pinned between the conversation log and the editor-context chip / attachments / input. It never scrolls.
- Visible only while a turn is in progress (session RUNNING or AWAITING_PERMISSION, incl. the v4 "waiting for editor context" pre-send moment); hidden when idle or dead.
- Contents: animated dots + label + elapsed seconds for the current phase + a **Stop** button (the interrupt that used to live in the header).
- Phases and labels:
  - `Starting…` — message handed to the CLI, no event from Claude yet.
  - `Thinking…` — a thinking content block started (stream_event content_block_start with block type `thinking`), or after a tool result arrived and nothing else has started yet.
  - `Running <Tool>…` — a tool_use is executing (from the assistant tool_use until its tool_result). Tool name sanitized (same cleaning as v4 context names, ≤ 40 chars).
  - `Writing…` — text deltas are streaming.
  - `Waiting for your answer` — a permission request or question card is open (AWAITING_PERMISSION); no timer, no dots; Stop still available.
- Elapsed seconds count from the start of the current phase, updated once per second in the page (no per-second bridge traffic). Phase changes come from Kotlin.
- Respects `prefers-reduced-motion` (no animation).
- Rendered with textContent only; Stop is a page-created element bound by id.

### Header
- The header no longer shows `Working…`, `Waiting for your permission` or `Ready`, and the header Stop button is removed.
- `Stopped` and the **Restart** button stay in the header for a dead session.

### Tabs
- A busy chat (RUNNING or AWAITING_PERMISSION) shows the platform's animated busy icon on its tool-window tab; the icon is cleared when the turn ends. Visible from any tab.

## Out of scope
- Showing thinking text.
- Token/cost counters in the strip.
