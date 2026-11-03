# Tether — Subagent cards

Date: 2026-11-03
Status: Approved in chat (inline expansion)
Extends: compact tool rows, working strip.

## Problem

When Claude starts a subagent (the `Agent` / `Task` tool), the chat shows one compact `Agent` row, and the subagent's
own tool calls leak into the main log as unrelated rows (e.g. `SubagentHandback`). There is no way to see what a
subagent is doing or what it reported.

## What the CLI sends (claude 2.1.317, recorded)

- `system/task_started`: `tool_use_id` (the Agent call), `description`, `subagent_type`, `is_backgrounded`, `prompt`.
- `system/task_progress`: `tool_use_id`, `usage.tool_uses`, `usage.duration_ms`, `last_tool_name`.
- `system/task_notification`: `tool_use_id`, `status` (`completed`, or another word on failure/stop), `usage`,
  `handback_report.text` (the agent's report; may be absent).
- The subagent's own `assistant` / `user` messages carry `parent_tool_use_id` = the Agent call's id. They arrive whole
  (no partial stream events). The subagent's first `user` message is its prompt; its last tool call is
  `SubagentHandback`, whose input is the report.
- A background agent's Agent tool_result arrives at once ("async launched"); a foreground one's when the agent is done.
  In both cases the result text is CLI boilerplate, not the report.

## Design

### Card (collapsed)
- An `Agent`/`Task` tool call renders as a card instead of a compact row.
- Head: status icon (spinner while running, ✓ done, ✗ failed/stopped), `Agent`, the description, the subagent type as a
  muted tag, and a `background` tag for background agents.
- Right side while running: `<last tool> · <n> tools · <s>s` from the latest progress (each part only when known);
  when finished: `<n> tools · <s>s`. Not ticking; it updates with each progress event.
- A disclosure triangle; clicking the head (or Enter/Space) expands/collapses. The expanded set is page state, kept
  across re-renders of the card.

### Card (expanded)
- `Prompt` (collapsed `<details>`, plain text, clipped like other previews).
- The subagent's items in arrival order: its text messages (markdown, sanitized like assistant text) and its tool rows
  (same compact rows / permission cards / question cards / diffs / images as the main log). Indented, with a left rule.
- The report at the bottom (markdown, sanitized), once known.
- The Agent call's own result preview is not shown when a report exists (it is boilerplate).

### Status
- Running from `task_started` until `task_notification`; then done (`completed`) or failed (anything else).
- Without task events (older CLI), the card follows the tool call's status as today.

### Routing
- Every item from a subagent carries `parentId` = the Agent call's tool_use_id. The page places it inside that card;
  an item whose card is not shown yet waits and is placed when the card appears. An item whose `parentId` changes
  (e.g. a permission request that arrived before its tool_use) moves into the card.
- Subagent events never change the main turn's phase or running-tool list; the strip keeps showing `Running Agent…`.
- `SubagentHandback` calls are not shown (their content is the report).
- The subagent's prompt `user` message is not shown as an item (the card has the prompt).
- A subagent tool that waits for permission or a question opens its card automatically.

### Limits
- Report and prompt: at most 100 000 characters kept (longer is cut with "…").
- Nested agents (an agent inside an agent) show as a card inside the outer card; no extra handling.

## Out of scope
- Stopping a single agent.
- Live-streaming a subagent's text (the CLI sends it whole).
- Resumed sessions: transcripts already show only user and assistant text, so no cards there.
- A ticking timer on the card.
