# Claude Chat — Images in and out (v5)

Date: 2026-11-03
Status: Approved in chat
Extends: v1–v4 specs. Everything there still applies unless changed here.

## Goal

Attach images to messages (paste, drop, pick) and see images in the chat: your own attachments, images Claude reads with its Read tool, and local project images referenced in Claude's markdown replies. Remote images stay blocked.

## Attaching

- Ways in: paste (Cmd+V of image data in the message box or anywhere in the chat page), drag-and-drop of image files or image data onto the chat page, and a 📎 button next to Send that opens the IDE file chooser (images only).
- Supported: PNG, JPEG, GIF, WebP — identified by magic bytes, not by extension or reported MIME.
- Limits: at most 10 images per message; each image after optional downscaling must be ≤ 5 MB. Images larger than 5 MB or with a long edge > 2000 px are downscaled (keeping aspect ratio) and re-encoded (PNG stays PNG unless still too large, then JPEG q=0.85; GIF/WebP are re-encoded as PNG if they need scaling). If an image can't be brought under the limit, it is rejected with a notice in the chat.
- Pending attachments appear as thumbnails above the message box, each with ×. Sending with only images and no text is allowed.
- Images are sent to the CLI as image content blocks in the user message (stream-json `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"…"}}`) alongside the text block (which carries the v1/v4 IDE notes + user text). Exact shape must be verified against the installed CLI (claude 2.1.317) with a probe before implementation.
- The user's bubble shows the thumbnails above the text.
- Prompt history (v2 Up/Down) stores text only.
- Resumed chats (transcript loading) show "[image]" placeholders for user-message image blocks; no image data is kept by the plugin outside the live session.

## Displaying

1. **Images Claude reads.** When a tool result contains image content blocks (e.g. Read on a .png), the tool row shows the image(s) inline (max display width = panel width, max height ~400 px). Exact tool-result shape must be verified with the probe.
2. **Local images in replies.** A markdown image `![alt](path)` in Claude's text is rendered when `path` resolves (v4/v2 FileLinker rules: relative to the chat's cwd or project content, or absolute inside them; no `..` escape) to an existing local file of a supported type (magic bytes) ≤ 5 MB. The plugin reads the file and hands the page a `data:` URL. Anything else (remote URLs, unresolved paths, non-images) renders as the alt text in plain text (no network request, no broken-image icon).

## Security

- CSP stays `img-src data:` — only plugin-provided data URLs can load; remote image URLs never load.
- DOMPurify config keeps forbidding `img` (and other media) in model markup. Images are created only as page-created (trusted) elements: markdown image tokens are replaced by placeholders before sanitizing and swapped for trusted elements after the plugin resolves them.
- All image bytes are validated by magic bytes in Kotlin before they reach the page or the CLI; data URLs are built by Kotlin with the validated MIME type.
- Size caps everywhere (attachment, tool result, local file).

## Out of scope

- Opening images full size (not requested).
- Images in Claude's responses that are not file references (e.g. generated images) — not produced by Claude Code.
- Persisting attachment images in prompt history.
