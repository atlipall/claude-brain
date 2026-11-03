# Claude Chat — Revert the conversation too

Date: 2026-11-03
Status: Approved in chat (approach "A": fork at the rewind point)
Extends: "Revert to before a message" (commit 1a61f97). Everything there still applies unless changed here.

## Goal

"Revert to here" on a message can also take the conversation back to before that message, so Claude forgets it and
everything after it. The user chooses each time what to revert.

## Behaviour

### The choice
- The confirmation dialog gets three buttons, plus Cancel: **Files and conversation**, **Files only**, and
  **Conversation only**.
- A message with no file checkpoint (checkout not tracked by Local History) still gets the link, and its dialog offers
  only **Conversation only**, with a line saying its files can't be reverted.
- Messages from an earlier IDE run (resumed history) get no link, as today.
- The revert is refused while a turn is running, as today.

### Reverting the conversation
1. Stop claude (it is idle: a running turn refuses the revert, so nothing is queued).
2. Remove the message and every item after it from the chat panel.
3. Start claude again with `--resume <session id> --resume-session-at <anchor> --fork-session`. The anchor is the last
   message from claude before the reverted message went out. When the reverted message is the chat's first, there is
   no anchor and claude starts a fresh session instead (no `--resume`).
4. Put the reverted message's text back in the input box (images are not restored).
5. The chat continues under the forked session's id, from `system/init`. A custom title is saved again under the new
   id. The old transcript stays in the history list as it was.
6. The "files were reverted" note goes to Claude with the next message only when the files were reverted too.

### Anchors
- `ChatSession` keeps the `uuid` of the newest `assistant` event in the stream (not `stream_event` partials, which
  carry their own ids). When a message goes out, that uuid becomes the message's anchor.
- A chat resumed in this IDE run starts from the last `assistant` uuid in its transcript, so a message sent after the
  resume has an anchor.

## Verified (spike, CLI 2.1.317, `-p` with stream-json input and output)
- `--resume S --resume-session-at U --fork-session` answers from the conversation up to and including `U` only, under
  a new session id.
- The forked transcript holds only the kept messages followed by the new ones; the original transcript is unchanged.
  `SessionStore` reads transcripts top to bottom, so the fork resumes correctly after an IDE restart with no changes
  there.
- The `uuid` on a stream-json `assistant` event is the transcript's uuid for that message.

## Out of scope
- Rewinding messages from an earlier IDE run.
- Restoring a reverted message's images to the input box.
- Truncating the transcript in place (approach B).
