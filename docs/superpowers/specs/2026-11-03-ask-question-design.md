# Claude Chat — Answering Claude's questions (v6)

Date: 2026-11-03
Status: Approved in chat
Extends: v1–v5 specs.

## Problem

When Claude uses the `AskUserQuestion` tool, the CLI (with `--permission-prompt-tool stdio`) sends a `can_use_tool` control request for it. The plugin renders every such request as a generic Allow / Allow for session / Deny card, so the user cannot answer the question.

## Behaviour

- A `can_use_tool` request whose `tool_name` is `AskUserQuestion` renders as a **question card** instead of the permission card.
- For each question in `input.questions`: its `header` as a small label, the `question` text, and its `options` (each `label` + `description`):
  - `multiSelect: false` → single choice (radio-style buttons);
  - `multiSelect: true` → checkboxes;
  - an **Other…** text field per question for a free-text answer (selecting/typing it replaces the option choice for single-select; adds to the list for multi-select).
- If an option has a `preview` (markdown/ASCII mockup or code), show it as preformatted text (textContent, monospace) when the option is focused/selected. Never rendered as HTML.
- Buttons: **Submit** (enabled when every question has an answer) and **Skip**.
- Submit → answer the control request with `allow` and an `updatedInput` that contains the original `questions` plus the user's answers, in the exact shape the CLI expects (to be verified by probe; the Agent SDK uses `answers: { "<question text>": "<label>" }`, with multi-select answers joined by ", ", and free text as the answer string).
- Skip → answer `deny` with message "The user skipped the question." so Claude continues.
- After answering, the card collapses to a one-line summary per question (`header: answer`), text only.
- "Allow for session" is never offered for AskUserQuestion; an AskUserQuestion request is never auto-allowed by a session allow-list.
- Interrupt/Stop while a question is pending denies it (existing behaviour for pending permissions).
- Resumed transcripts: unchanged (questions/answers are part of tool calls; no special rendering needed).

## Verification first

Before implementing, probe the installed CLI (claude 2.1.317) in stream-json mode with `--permission-prompt-tool stdio`: make Claude call AskUserQuestion (e.g. "Ask me which colour I prefer using the AskUserQuestion tool, then say the answer.") and record (a) the exact `can_use_tool` request for AskUserQuestion, (b) the `updatedInput` answer shape that makes Claude receive the answer (confirm by its reply), (c) what the tool_result looks like. Use the verified shape.

## Security

- All question/option/header/description/preview text is model-controlled → rendered via textContent only.
- Only page-created (trusted) controls can submit; the bridge validates the submitted answer message (request id must be a pending AskUserQuestion; answers keyed by the request's own question texts; option labels must be one of that question's labels unless the answer is the free-text "Other"; strings length-capped).
- v1–v5 security properties unchanged (CSP, SANITIZE, trusted WeakSet, capture-phase handlers, navigation guard, openUrl http(s), env stripping).
