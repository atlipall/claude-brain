# Claude Chat — Compact tool rows (v7)

Date: 2026-11-03
Status: Approved in chat (mockup option "A")
Extends: v1–v6 specs.

## Problem

Every tool call renders as a full card: the whole command as a heading, a "done" badge and Input/Result expanders. Long Bash commands dominate the conversation.

## Design

Each tool call is one line:

`<status icon> <Tool> <description> [side]`

- **Status icon:** spinner while running, ✓ done, ✗ failed, ⊘ denied. While a permission or question card is open, the existing card is shown in full instead of the row (unchanged behaviour).
- **Tool:** the tool name, bold.
- **Description** (single line, ellipsis when too long; textContent only):
  - Bash: `input.description` when present (Claude Code sends one), else the first line of `input.command`.
  - Read / Edit / MultiEdit / Write / NotebookEdit: the file as a clickable file link (existing trusted link behaviour, short path, full path in the tooltip).
  - Grep: `pattern` (and ` in <path>` when present). Glob: `pattern`. WebFetch: the URL's host + path. WebSearch: `query`. Task/Agent: `description`. TodoWrite: "Update todos".
  - Any other tool: the first non-empty string field of its input, else nothing.
- **Side:** "View diff" link for edits with a diff (existing behaviour); ` · failed` text for errors; a ▸/▾ toggle when there are details.
- **Details (collapsed by default, toggled by clicking the row):** the full input (Bash: the command; Edit: old/new; Write: content; others: JSON) and the result preview, using the existing rendering. Expanded state is kept across re-renders of the same item.
- **Images from tool results** (v5) stay visible under the row, not collapsed.
- Clicking a file link or "View diff" does not toggle the row.

## Unchanged

Permission and question cards; security properties (CSP, SANITIZE, trusted WeakSet gating, capture-phase guards, navigation guard, openUrl http(s), textContent).

## Out of scope

Grouping consecutive tool calls (mockup option B).
