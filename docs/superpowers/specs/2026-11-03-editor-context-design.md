# Claude Chat — Editor context ("what does this function do") (v4)

Date: 2026-11-03
Status: Approved in chat ("Always, with a chip")
Extends: v1–v3 specs. Everything there still applies unless changed here.

## Goal

The user can ask "what does this function do?" (or "fix this", "explain the selection") without naming the file or symbol. Each message carries where the user is looking in the editor.

## Context captured

From the **context editor** for the chat:
- the focused text editor, or — when focus is in the chat tool window — the last text editor that had focus in this project (tracked by the plugin);
- only text editors backed by a local file; otherwise no context.

Fields:
- `path`: file path relative to the chat's cwd. If the file is outside the chat's checkout but has a counterpart there (v3 `CheckoutMapper.counterpart`), use the counterpart's relative path; otherwise, if the file is outside the chat's cwd and has no counterpart, no context.
- `caret`: 1-based line and column of the primary caret.
- `selection`: 1-based start and end lines of the primary selection, if non-empty.
- `symbol`: the innermost enclosing structural element at the caret — name (as presented by the IDE) and 1-based start–end lines — found via the language-agnostic structure model (Structure View / breadcrumbs); none if no element encloses the caret or the language has no structure model. Prefer function/method-like elements; if the innermost element is not function-like (e.g. a variable), still report it, but include its enclosing function-like ancestor too when one exists (`symbol` + `container`).

## Message note

Prepended to the message sent to Claude (not shown in the user's bubble), after the v1 edit note if both are present, one paragraph:

```
[IDE] The user is looking at src/billing/invoice.ts, caret at line 142:17, inside function `calculateTotals` (lines 120–168). Selection: lines 140–145.
```
- Omit the `inside …` clause when there is no symbol; use the IDE's kind word when available (function/method/class/…), otherwise "inside `name`".
- Omit `Selection: …` when there is no selection.
- Only location is sent, never file contents. Unsaved changes are saved first (the existing send path already calls saveAllDocuments via the edit tracker).

## Chip

- Above the message input, a single-line chip shows the context that will be attached: `<file name> › <symbol name> · L<line>` (or `<file name> · L<line>` without a symbol; `· L140–145` when there is a selection), plus a × button.
- The chip updates live (debounced ~150 ms) as the caret/selection/editor changes.
- × skips the context for the next message only; the chip shows a muted "context off for next message — click to restore" state; after sending, it returns to live.
- No chip (hidden) when there is no context.
- Rendered with textContent only (v1 security properties unchanged).

## Settings

"Attach editor context to messages" (default on) in Settings > Tools > Claude Chat. Off → no chip, no note.

## Out of scope

- Sending code contents.
- Multiple carets beyond the primary one.
- Context from non-text editors (images, diffs).
