# Revert the Conversation Too: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** "Revert to here" asks whether to revert the files, the conversation, or both; reverting the conversation makes Claude forget that message and everything after it.

**Architecture:** `ChatSession` records, for every message it sends, the uuid of the newest main-thread `assistant` line before it (the message's *anchor*). A conversation rewind stops claude and starts it again with `--resume <old id> --resume-session-at <anchor> --fork-session` (or a fresh session when there is no anchor), and `Chat` drops the message and what follows from the panel and puts the text back in the input box. `TurnCheckpoints` asks which revert the user wants and reverts the files if chosen.

**Tech Stack:** Kotlin, IntelliJ Platform (Local History, `Messages`), JUnit 4 (plain unit tests) and `BasePlatformTestCase` (IDE tests), Gradle through `./gw`.

**Spec:** `docs/superpowers/specs/2026-11-03-rewind-conversation-design.md`

## Global Constraints

- Claude Code CLI 2.1.317; the process runs as `claude -p --input-format stream-json --output-format stream-json …` (see `ClaudeCommand.build`).
- Flags, exactly: `--resume <session id>`, `--resume-session-at <message uuid>`, `--fork-session`.
- Dialog buttons, exactly: `Files and Conversation`, `Files Only`, `Conversation Only`, `Cancel` (platform title case).
- Messages from an earlier IDE run (resumed history items, ids `h-N`) get no revert link.
- The revert is refused while the chat is busy (`Chat.busy`).
- Run tests with `./gw test --tests '<class>'` (the wrapper uses WebStorm's bundled JBR). Test results: `build/test-results/test/TEST-<class>.xml`.
- Commit messages: imperative subject line, prose body explaining what and why (see `git log`), no trailers.

## File Structure

- `src/main/kotlin/claudechat/cli/CliEvent.kt`: new `CliEvent.AssistantMessage(uuid)`, emitted first for each main-thread `assistant` line.
- `src/main/kotlin/claudechat/cli/ClaudeCommand.kt`: `SessionLaunch.resumeAt` and `SessionLaunch.fork`, turned into flags.
- `src/main/kotlin/claudechat/session/ChatSession.kt`: anchors, `canRewindTo`, `rewind`, the pending fork.
- `src/main/kotlin/claudechat/session/SessionStore.kt`: `lastAssistantUuid` for resumed chats.
- `src/main/kotlin/claudechat/session/Chat.kt`: `RevertChoice`, the new `Checkpoints.revert` signature, `revertTo` doing the conversation part, `resumeAnchor` passed through.
- `src/main/kotlin/claudechat/session/Chats.kt`: passes `resumeAnchor`.
- `src/main/kotlin/claudechat/ide/TurnCheckpoints.kt`: the three-way dialog, file revert, notes.
- `src/main/resources/webview/chat.js`: link tooltip.
- `README.md`, the spec: wording.

---

### Task 1: The CLI side (anchor uuids in, rewind flags out)

**Files:**
- Modify: `src/main/kotlin/claudechat/cli/CliEvent.kt` (the `CliEvent` interface, lines 11-58; `CliParser.assistant`, lines 118-130)
- Modify: `src/main/kotlin/claudechat/cli/ClaudeCommand.kt` (`SessionLaunch`, lines 18-23; `ClaudeCommand.build`, lines 25-38)
- Modify: `src/test/kotlin/claudechat/testutil/Fx.kt` (`assistantText`, `assistantThinking`, `toolUse`: add a `uuid` parameter)
- Test: `src/test/kotlin/claudechat/cli/CliParserTest.kt`, `src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt`

**Interfaces:**
- Produces: `CliEvent.AssistantMessage(val uuid: String)`; `SessionLaunch(resumeId, permissionMode, cwd, worktree = null, resumeAt: String? = null, fork: Boolean = false)`; Fx helpers `assistantText(msg, text, uuid = "u5")`, `assistantThinking(msg, uuid = "u5")`, `toolUse(msg, toolUseId, name, inputJson, uuid = "u6")`.

- [ ] **Step 1: Add the uuid parameter to the Fx helpers**

In `Fx.kt`, change the three helpers so the uuid is a parameter (keep the defaults so existing callers are unchanged):

```kotlin
    fun assistantText(msg: String, text: String, uuid: String = "u5") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""

    fun assistantThinking(msg: String, uuid: String = "u5") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"sig"}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""

    fun toolUse(msg: String, toolUseId: String, name: String, inputJson: String, uuid: String = "u6") =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"$name","input":$inputJson,"caller":{"type":"direct"}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"$uuid"}"""
```

- [ ] **Step 2: Write the failing parser tests**

In `CliParserTest.kt`, add:

```kotlin
    @Test fun aMainThreadAssistantLineReportsItsUuidFirst() {
        assertEquals(
            listOf(CliEvent.AssistantMessage("a-1"), CliEvent.AssistantText("m1", "DONE")),
            CliParser.parse(Fx.assistantText("m1", "DONE", uuid = "a-1")))
        // A line with only thinking shows nothing, but it is still a message a rewind can resume at.
        assertEquals(listOf(CliEvent.AssistantMessage("a-2")), CliParser.parse(Fx.assistantThinking("m1", uuid = "a-2")))
    }

    @Test fun aSubagentsAssistantLineReportsNoUuid() {
        // Subagent messages live in their own transcript; the main conversation can't be resumed at them.
        assertTrue(CliParser.parse(Fx.subagentText("toolu_1", "m2", "hi")).none { it is CliEvent.AssistantMessage })
    }
```

Then fix the existing assistant-line assertions, which now get the uuid event first. Add this helper next to `one()`:

```kotlin
    /** The events of an assistant line after its [CliEvent.AssistantMessage]. */
    private fun blocks(line: String): List<CliEvent> = CliParser.parse(line).filterNot { it is CliEvent.AssistantMessage }
```

and, in `assistantTextAndToolUse` and in every other test that calls `one(...)` on a line built with `Fx.assistantText`, `Fx.toolUse` or a hand-written `"type":"assistant"` line, replace `one(x)` with `blocks(x).single()`. Replace `assertTrue(CliParser.parse(Fx.assistantThinking("m1")).isEmpty())` with `assertTrue(blocks(Fx.assistantThinking("m1")).isEmpty())`. Find them all with:

Run: `grep -n 'one(Fx.assistantText\|one(Fx.toolUse\|"type":"assistant"\|assistantThinking' src/test/kotlin/claudechat/cli/CliParserTest.kt`

- [ ] **Step 3: Write the failing command tests**

In `ClaudeCommandTest.kt`, add:

```kotlin
    @Test fun rewindResumesAtAMessageInAFork() = assertEquals(
        base + listOf("--resume", "abc", "--resume-session-at", "u-9", "--fork-session"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", null, "/p", resumeAt = "u-9", fork = true), emptyList()))
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gw test --tests 'claudechat.cli.CliParserTest' --tests 'claudechat.cli.ClaudeCommandTest'`
Expected: compilation fails (`AssistantMessage`, `resumeAt` and `fork` are unknown).

- [ ] **Step 5: Implement**

In `CliEvent.kt`, inside `sealed interface CliEvent`, after `AssistantText`:

```kotlin
    /** A main-conversation assistant message was recorded under [uuid]; a rewind can resume the session at it. */
    data class AssistantMessage(val uuid: String) : CliEvent
```

Replace `CliParser.assistant` with:

```kotlin
    private fun assistant(obj: JsonObject): List<CliEvent> {
        val message = obj.obj("message") ?: return emptyList()
        val messageId = message.str("id") ?: ""
        val parent = obj.str("parent_tool_use_id")
        val blocks = message.arr("content").orEmpty().mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> CliEvent.AssistantText(messageId, b.str("text") ?: "", parent)
                "tool_use" -> CliEvent.ToolUse(b.str("id") ?: "", b.str("name") ?: "", b.obj("input") ?: EMPTY, parent)
                else -> null
            }
        }
        val uuid = obj.str("uuid")?.takeIf { parent == null && it.isNotEmpty() } ?: return blocks
        return listOf(CliEvent.AssistantMessage(uuid)) + blocks
    }
```

In `ClaudeCommand.kt`, extend `SessionLaunch` and its KDoc:

```kotlin
/**
 * What a new CLI process should start as. [cwd] is the process directory; [worktree] asks claude to
 * create `<cwd>/.claude/worktrees/<name>` on branch `worktree-<name>` and run there. [resumeAt] resumes [resumeId]
 * only up to and including that assistant message; [fork] continues under a new session id, leaving [resumeId]'s
 * transcript as it was.
 */
data class SessionLaunch(
    val resumeId: String?,
    val permissionMode: PermissionMode?,
    val cwd: String,
    val worktree: String? = null,
    val resumeAt: String? = null,
    val fork: Boolean = false,
)
```

and in `build`, after the `--resume` line:

```kotlin
        launch.resumeAt?.let { add("--resume-session-at"); add(it) }
        if (launch.fork) add("--fork-session")
```

In `ChatSession.handle` (`src/main/kotlin/claudechat/session/ChatSession.kt`, the `when (event)`), add a branch so the new event is accepted; Task 2 gives it a body:

```kotlin
            is CliEvent.AssistantMessage -> {}
```

(If the `when` is not exhaustive over `CliEvent`, the branch is still harmless; check with `grep -n "is CliEvent.Ignored" src/main/kotlin/claudechat/session/ChatSession.kt` to see where unknown events go.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gw test --tests 'claudechat.cli.*'`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/claudechat/cli src/main/kotlin/claudechat/session/ChatSession.kt src/test/kotlin/claudechat/cli src/test/kotlin/claudechat/testutil/Fx.kt
git commit -m "Read assistant message uuids; launch claude resumed at one, forked" -m "Each main-conversation assistant line now reports its uuid before its blocks, so the session can tell where a rewind should resume. SessionLaunch gains resumeAt and fork, which become --resume-session-at and --fork-session."
```

---

### Task 2: ChatSession anchors and rewind

**Files:**
- Modify: `src/main/kotlin/claudechat/session/ChatSession.kt` (constructor at lines 99-111; fields after line 160; `start()` at 165; `send()` at 213; queue drain at 599; `handle` `SystemInit` branch)
- Test: `src/test/kotlin/claudechat/session/ChatSessionTest.kt`

**Interfaces:**
- Consumes: `CliEvent.AssistantMessage(uuid)`, `SessionLaunch.resumeAt`, `SessionLaunch.fork` (Task 1).
- Produces:
  - constructor parameter `resumeAnchor: String? = null`, last in the list (after `background`) so existing positional calls still compile: the uuid of the newest assistant message of the resumed transcript.
  - `fun canRewindTo(itemId: String): Boolean`: true for a `UserText` this session sent (in this run).
  - `fun rewind(itemId: String): Boolean`: false (and nothing changes) when a turn is open or the message is unknown; otherwise restarts claude as of before that message and returns true.

- [ ] **Step 1: Write the failing tests**

Add `resumeAnchor: String? = null` to the test's `session(...)` helper and pass it to `ChatSession(... resumeAnchor = resumeAnchor ...)`. Then add:

```kotlin
    // --- rewind ---

    private fun userIds() = listener.current().filterIsInstance<ChatItem.UserText>().map { it.id }

    /** One finished turn: [text] goes out, claude answers with assistant message [uuid]. */
    private fun turn(s: ChatSession, text: String, uuid: String) {
        s.send(text)
        conn.emit(Fx.assistantText("m-$uuid", "ok", uuid = uuid), Fx.result())
    }

    @Test fun rewindResumesForkedAtTheLastAnswerBeforeTheMessage() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        turn(s, "two", "a-2")
        val old = conn
        assertTrue(s.rewind(userIds()[1]))
        assertTrue(old.stopped)
        assertEquals(SessionLaunch("sess-1", null, "/proj", resumeAt = "a-1", fork = true), launches.last())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun rewindingTheFirstMessageStartsAFreshSession() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        assertTrue(s.rewind(userIds()[0]))
        assertEquals(SessionLaunch(null, null, "/proj"), launches.last())
        assertNull(s.sessionId)
    }

    @Test fun theForkIsKeptUntilClaudeReportsItsNewSession() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        turn(s, "two", "a-2")
        s.rewind(userIds()[1])
        // The id is unknown until system/init: nothing may be saved under the old one meanwhile.
        assertNull(s.sessionId)
        conn.exit(1, "")
        s.restart()
        assertEquals(SessionLaunch("sess-1", null, "/proj", resumeAt = "a-1", fork = true), launches.last())
        conn.emit(Fx.init("sess-2"))
        assertEquals("sess-2", s.sessionId)
        conn.exit(1, "")
        s.restart()
        assertEquals(SessionLaunch("sess-2", null, "/proj"), launches.last())
    }

    @Test fun aResumedChatsFirstNewMessageIsAnchoredAtTheTranscriptsLastAnswer() {
        val s = session(resumeId = "sess-1", resumeAnchor = "a-0")
        s.send("more")
        conn.emit(Fx.result())
        assertTrue(s.rewind(userIds()[0]))
        assertEquals(SessionLaunch("sess-1", null, "/proj", resumeAt = "a-0", fork = true), launches.last())
    }

    @Test fun aRunningTurnIsNotRewound() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        s.send("one")
        assertFalse(s.rewind(userIds()[0]))
        assertEquals(1, launches.size)
    }

    @Test fun onlyMessagesThisSessionSentCanBeRewoundTo() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        assertTrue(s.canRewindTo(userIds()[0]))
        assertFalse(s.canRewindTo("h-0"))
        assertFalse(s.rewind("h-0"))
        // The rewound message and those after it are gone, so they can't be rewound to again.
        s.rewind(userIds()[0])
        assertFalse(s.canRewindTo(userIds()[0]))
    }

    @Test fun aSubagentsAnswerIsNotAnAnchor() {
        val s = session()
        conn.emit(Fx.init("sess-1"))
        turn(s, "one", "a-1")
        s.send("two")
        conn.emit(Fx.subagentText("toolu_1", "m9", "sub"), Fx.result())
        s.send("three")
        conn.emit(Fx.result())
        s.rewind(userIds()[2])
        assertEquals("a-1", launches.last().resumeAt)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.ChatSessionTest'`
Expected: compilation fails (`resumeAnchor`, `rewind`, `canRewindTo` are unknown).

- [ ] **Step 3: Implement**

Constructor: add as the last parameter, after `private val background: (Runnable) -> Unit = { it.run() },`:

```kotlin
    /** The uuid of the resumed transcript's newest assistant message: where a rewind to its first new message resumes. */
    resumeAnchor: String? = null,
```

Fields, after `readOnlyTools`:

```kotlin
    /** The newest main-conversation assistant message's uuid: where a rewind to the next message would resume. */
    private var lastAnswer: String? = resumeAnchor
    /** Each message this session sent, by item id, to the answer it followed (null: it was the first), oldest first. */
    private val anchors = LinkedHashMap<String, String?>()
    /** Where to resume [from] until the forked session reports its id; null when not rewinding. */
    private class Fork(val from: String, val at: String)
    private var fork: Fork? = null
```

In `start()`, change the connect line to:

```kotlin
                val f = fork
                val conn = connect(SessionLaunch(f?.from ?: sessionId, mode, cwd, if (cwdReported) null else worktree, f?.at, f != null))
```

In `handle`, give the Task 1 branch its body and clear the fork on `SystemInit`:

```kotlin
            is CliEvent.AssistantMessage -> lastAnswer = event.uuid
```

```kotlin
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) { sessionId = event.sessionId; fork = null }
                // ... rest unchanged
```

Record the anchor wherever a message goes out. In `send()`:

```kotlin
            if (state == SessionState.IDLE) {
                val item = ChatItem.UserText(nextId("user"), text, thumbs)
                anchors[item.id] = lastAnswer
                emit(item)
```

and in the queue drain (near line 599):

```kotlin
            val item = ChatItem.UserText(nextId("user"), next.shown.text, next.shown.images)
            anchors[item.id] = lastAnswer
            emit(item)
```

Add the two public methods after `restart()`:

```kotlin
    /** Whether [itemId] is a message this session sent, so the conversation can be taken back to before it. */
    fun canRewindTo(itemId: String): Boolean = synchronized(lock) { itemId in anchors }

    /**
     * Takes the conversation back to before message [itemId]: claude restarts resumed at the answer before it, in a
     * fork (the old transcript stays as it was), or as a fresh session when it was the first message. False, changing
     * nothing, while a turn is open or for a message this session didn't send.
     */
    fun rewind(itemId: String): Boolean = synchronized(lock) {
        if (turnOpen || itemId !in anchors) return false
        val at = anchors[itemId]
        val from = sessionId ?: fork?.from
        val keys = anchors.keys.toList()
        keys.subList(keys.indexOf(itemId), keys.size).forEach(anchors::remove)
        generation++
        connection?.stop()
        connection = null
        mcpServer = null
        readOnlyTools = emptySet()
        initRequestId = null
        contextRequestId = null
        pendingPermissions.clear()
        runningTools.clear()
        turnEdits.clear()
        phase = null
        lastAnswer = at
        fork = if (at != null && from != null) Fork(from, at) else null
        sessionId = null
        start()
        reportActivity()
        true
    }
```

Note: the session is idle when `rewind` runs (`turnOpen` is false), so the queue is empty and there is nothing to drop. A DEAD session can be rewound too: `start()` brings it back.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.ChatSessionTest'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/session/ChatSession.kt src/test/kotlin/claudechat/session/ChatSessionTest.kt
git commit -m "Rewind a session to before one of its messages" -m "Each message the session sends remembers the assistant message it followed. rewind() restarts claude resumed at that message in a fork (--resume-session-at, --fork-session), or as a fresh session for the first message. The session has no id until the fork's system/init, so nothing is saved under the old one, and a restart before then forks again."
```

---

### Task 3: Anchors for chats resumed in this IDE run

**Files:**
- Modify: `src/main/kotlin/claudechat/session/SessionStore.kt` (after `transcript`, line 68)
- Modify: `src/main/kotlin/claudechat/session/Chat.kt` (constructor, and the `ChatSession(...)` call at line 190)
- Modify: `src/main/kotlin/claudechat/session/Chats.kt` (`open`, line 60)
- Test: `src/test/kotlin/claudechat/session/SessionStoreTest.kt`

**Interfaces:**
- Consumes: `ChatSession(resumeAnchor = …)` (Task 2).
- Produces: `fun SessionStore.lastAssistantUuid(cwd: String, sessionId: String): String?`; `Chat` constructor parameter `resumeAnchor: String? = null`.

- [ ] **Step 1: Write the failing test**

In `SessionStoreTest.kt` (helpers `write`, `user`, `assistant` are at the top of the class), add:

```kotlin
    @Test fun lastAssistantUuidIsTheNewestMainConversationAnswer() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1000,
            user("hi"),
            """{"type":"assistant","uuid":"a-1","message":{"role":"assistant","content":[{"type":"text","text":"one"}]}}""",
            """{"type":"assistant","uuid":"a-2","message":{"role":"assistant","content":[{"type":"text","text":"two"}]}}""",
            """{"type":"assistant","uuid":"side","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"sub"}]}}""",
            """{"type":"custom-title","customTitle":"x"}""",
        )
        assertEquals("a-2", store.lastAssistantUuid(cwd, "s1"))
        assertEquals(null, store.lastAssistantUuid(cwd, "missing"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gw test --tests 'claudechat.session.SessionStoreTest'`
Expected: compilation fails (`lastAssistantUuid` is unknown).

- [ ] **Step 3: Implement**

In `SessionStore.kt`, after `transcript`:

```kotlin
    /** The uuid of the transcript's newest main-conversation assistant message (subagents' are skipped), or null. */
    fun lastAssistantUuid(cwd: String, sessionId: String): String? {
        val file = transcriptFile(cwd, sessionId)
        if (!file.isFile) return null
        return file.useLines { lines ->
            lines.mapNotNull(::parseJsonObject)
                .filter { it.str("type") == "assistant" && it.bool("isSidechain") != true }
                .mapNotNull { it.str("uuid") }
                .lastOrNull()
        }
    }
```

(Import `claudechat.cli.bool` if the file does not already; check with `grep -n "^import" src/main/kotlin/claudechat/session/SessionStore.kt`.)

In `Chat.kt`, add a constructor parameter after `history: List<ChatItem>,`:

```kotlin
    /** The uuid of the resumed transcript's newest assistant message; null for a new chat. */
    resumeAnchor: String? = null,
```

and pass it as the last argument of the `ChatSession(...)` call:

```kotlin
    val session = ChatSession(connector, listener, spec.cwd, spec.resumeId, initialMode, spec.worktree, log, ide, background, resumeAnchor)
```

In `Chats.open`, pass `resumeAnchor = spec.resumeId?.let { store.lastAssistantUuid(spec.cwd, it) },` to `Chat(...)`, next to `history = history,`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.*'`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/claudechat/session src/test/kotlin/claudechat/session/SessionStoreTest.kt
git commit -m "Anchor a resumed chat's new messages at its transcript's last answer" -m "A chat resumed from history starts with the uuid of the transcript's newest main-conversation assistant message, so the first message sent after the resume can be rewound to as well."
```

---

### Task 4: The three-way revert

**Files:**
- Modify: `src/main/kotlin/claudechat/session/Chat.kt` (`Checkpoints` interface at lines 39-46; `onItem` at line 168; `revertTo` and `showRevertable` at lines 371-382)
- Modify: `src/main/kotlin/claudechat/ide/TurnCheckpoints.kt` (`mark`, `revert`, notes)
- Modify: `src/main/resources/webview/chat.js:179` (tooltip)
- Modify: `README.md:51-54`, `docs/superpowers/specs/2026-11-03-rewind-conversation-design.md` (notes)
- Modify: `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt:32` (comment only)
- Test: `src/test/kotlin/claudechat/ide/TurnCheckpointsTest.kt`

**Interfaces:**
- Consumes: `ChatSession.canRewindTo`, `ChatSession.rewind` (Task 2).
- Produces:
  - in `Chat.kt`: `enum class RevertChoice(val label: String, val files: Boolean, val conversation: Boolean) { FILES_AND_CONVERSATION("Files and Conversation", true, true), FILES("Files Only", true, false), CONVERSATION("Conversation Only", false, true) }`
  - `interface Checkpoints { fun mark(chat: Chat, item: ChatItem.UserText); fun revert(chat: Chat, item: ChatItem.UserText, conversation: Boolean): RevertChoice? }`
  - `TurnCheckpoints.hasCheckpoint(chatId: Int, itemId: String): Boolean` (tests wait on it).

Behaviour, one line each:
- Every message the session sends shows the link at once (when the chat has `checkpoints`); the background `mark` only decides whether the files part is offered.
- Dialog buttons, in order: `Files and Conversation` (files and conversation both possible), `Files Only` (a checkpoint exists), `Conversation Only` (the session can rewind to the message), `Cancel` last. Button 0 is the default.
- No checkpoint and no conversation: the existing "This message is too old to revert to." info, nothing else.
- Notes to Claude with the next message: `FILES` the existing "reverted the files" note; `CONVERSATION` a note that the files may still hold changes from the dropped turns; `FILES_AND_CONVERSATION` none (Claude's memory and the files now agree).
- Conversation part, in `Chat`: drop the message and every later item, `view.reset`, mark a custom title unsaved, `session.rewind`, put the message text in the input box.

- [ ] **Step 1: Write the failing tests**

In `TurnCheckpointsTest.kt`:

Replace the wait in `endTurn` (the link now shows at once, so wait for the checkpoint instead):

```kotlin
    /** Ends the turn of the message just sent, once its checkpoint is in (it is put in the background). */
    private fun endTurn(chat: claudechat.session.Chat): ChatItem.UserText {
        val item = sent(chat).last()
        PlatformTestUtil.waitWithEventsDispatching("The message was not marked",
            { TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, item.id) }, 10)
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return sent(chat).last()
    }
```

Make `sent` return every message (several tests now send two), and adjust its callers to `.single()` or `.last()`:

```kotlin
    /** The chat's messages as the page would get them. */
    private fun sent(chat: claudechat.session.Chat): List<ChatItem.UserText> {
        var shown = emptyList<ChatItem>()
        chat.attach(object : claudechat.session.SessionView {
            override fun reset(items: List<ChatItem>) { shown = items }
            override fun item(item: ChatItem) {}
            override fun state(state: claudechat.session.SessionState, sessionId: String?, mode: claudechat.cli.PermissionMode?) {}
            override fun insert(text: String) { inserted += text }
        })
        return shown.filterIsInstance<ChatItem.UserText>()
    }
    private val inserted = mutableListOf<String>()
```

Add a dialog helper and record what the dialog said:

```kotlin
    private var asked: String? = null

    /** Answers the revert dialog with button [index] (see RevertChoice order; Cancel is last). */
    private fun answer(index: Int) = TestDialogManager.setTestDialog { message -> asked = message; index }
```

Update the existing tests to pick explicitly: in `testRevertRestoresTheFilesAsTheyWereBeforeTheMessage` and `testAMessageSentWhileAnotherFolderChangesCanStillBeRevertedTo` replace `TestDialogManager.setTestDialog(TestDialog.YES)` with `answer(1) // Files Only`; in `testARunningTurnIsNotReverted` with `answer(1)`; in `testCancellingTheQuestionChangesNothing` replace `TestDialog.NO` with `answer(3) // Cancel`. Replace `sent(chat).revertable` waits with `TurnCheckpoints.getInstance(project).hasCheckpoint(chat.id, sent(chat).single().id)`, and `sent(chat).id` with `sent(chat).single().id`.

Then add:

```kotlin
    fun testRevertingFilesAndConversationDropsTheMessageAndRefillsTheInputBox() {
        write("f.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        conn.emit(Fx.init("sess-1"))
        chat.send("first")
        conn.emit(Fx.assistantText("m1", "ok", uuid = "a-1"))
        endTurn(chat)
        val second = turn(chat, "second")
        write("f.txt", "after")
        val old = conn
        answer(0) // Files and Conversation
        chat.revertTo(second.id)
        assertEquals("before", read("f.txt"))
        assertTrue(old.stopped)
        assertEquals(listOf("first"), sent(chat).map { it.text })
        assertEquals(listOf("second"), inserted)
        assertNull("files and memory agree again", TurnCheckpoints.getInstance(project).consumeNote(chat.id))
    }

    fun testRevertingOnlyTheConversationLeavesTheFiles() {
        write("g.txt", "before")
        val chat = chats.open(ChatSpec.inProject(root.path))
        conn.emit(Fx.init("sess-1"))
        val item = turn(chat, "change it")
        write("g.txt", "after")
        answer(2) // Conversation Only
        chat.revertTo(item.id)
        assertEquals("after", read("g.txt"))
        assertTrue(sent(chat).isEmpty())
        assertTrue(TurnCheckpoints.getInstance(project).consumeNote(chat.id)!!.contains("may still"))
    }

    fun testAnUntrackedCheckoutOffersOnlyTheConversation() {
        val outside = VfsUtil.findFile(Files.createTempDirectory("untracked"), true)!! // not a content root
        val chat = chats.open(ChatSpec.inProject(outside.path))
        conn.emit(Fx.init("sess-1"))
        chat.send("change it")
        conn.emit(Fx.result())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val item = sent(chat).single()
        assertTrue("the link shows without a checkpoint", item.revertable)
        answer(0) // the only choice: Conversation Only
        chat.revertTo(item.id)
        assertTrue(asked!!.contains("can't be reverted"))
        assertTrue(sent(chat).isEmpty())
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gw test --tests 'claudechat.ide.TurnCheckpointsTest'`
Expected: compilation fails (`hasCheckpoint` is unknown).

- [ ] **Step 3: Implement the model side in `Chat.kt`**

Replace the `Checkpoints` interface with:

```kotlin
/** What "Revert to here" takes back to before a message, as the user chose it. */
enum class RevertChoice(val label: String, val files: Boolean, val conversation: Boolean) {
    FILES_AND_CONVERSATION("Files and Conversation", true, true),
    FILES("Files Only", true, false),
    CONVERSATION("Conversation Only", false, true),
}

/** Marks the files' state before each message of a chat, and reverts to such a mark. */
interface Checkpoints {
    /** Marks the files before [item] goes out, when their folder can be reverted. Pooled thread, outside every lock. */
    fun mark(chat: Chat, item: ChatItem.UserText)

    /**
     * Asks what to revert to before [item] ([conversation]: whether the conversation can be), and reverts the files
     * if that is chosen. Null when nothing more is to happen (cancelled, refused, or the file revert failed). EDT.
     */
    fun revert(chat: Chat, item: ChatItem.UserText, conversation: Boolean): RevertChoice?
}
```

In `onItem`, show the link at once and mark in the background:

```kotlin
        override fun onItem(item: ChatItem) {
            // Every UserText the session reports is a message going out now (history comes through the constructor),
            // so the conversation can be rewound to before it; the files' mark is IDE work, off this thread.
            val shown = if (item is ChatItem.UserText && checkpoints != null) item.copy(revertable = true) else item
            synchronized(lock) { items[shown.id] = shown }
            view?.item(shown)
            if (shown is ChatItem.UserText) checkpoints?.let { c -> background { c.mark(this@Chat, shown) } }
        }
```

Replace `revertTo` and delete `showRevertable`:

```kotlin
    /** Reverts the files, the conversation or both to before message [itemId], as the user picks. EDT. */
    fun revertTo(itemId: String) {
        val item = synchronized(lock) { items[itemId] } as? ChatItem.UserText ?: return
        if (!item.revertable) return
        val choice = checkpoints?.revert(this, item, session.canRewindTo(itemId)) ?: return
        if (choice.conversation) rewindTo(item)
    }

    /** Claude forgets [item] and what followed; the panel drops them and the message goes back into the input box. */
    private fun rewindTo(item: ChatItem.UserText) {
        if (!session.canRewindTo(item.id)) return
        val kept = synchronized(lock) {
            val ids = items.keys.toList()
            ids.subList(ids.indexOf(item.id), ids.size).forEach(items::remove)
            items.values.toList()
        }
        view?.reset(kept)
        // The fork gets a new session id; a name the user gave is saved under it once claude reports it.
        if (customTitle != null) titleUnsaved.set(true)
        session.rewind(item.id)
        item.text.takeIf { it.isNotBlank() }?.let(::insert)
    }
```

- [ ] **Step 4: Implement the IDE side in `TurnCheckpoints.kt`**

`mark` returns nothing now: change its signature to `override fun mark(chat: Chat, item: ChatItem.UserText)`, turn `false` results into `return`, drop the final `true`, and keep the `catch` logging. The body becomes:

```kotlin
    override fun mark(chat: Chat, item: ChatItem.UserText) {
        try {
            val dir = LocalFileSystem.getInstance().refreshAndFindFileByPath(chat.cwd)
            if (dir == null || !tracked(dir)) return
            // Writes the previous turn made behind the IDE's back belong before this label, not after it.
            VfsUtil.markDirtyAndRefresh(false, true, true, dir)
            val message = shorten(item.text)
            // (keep the existing Local History comment and the WriteAction.computeAndWait label code here)
            val label = WriteAction.computeAndWait<Label, RuntimeException> {
                LocalHistory.getInstance().putSystemLabel(project, "Tether: before “$message”")
            }
            val forChat = checkpoints.computeIfAbsent(chat.id) {
                java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Checkpoint>() {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Checkpoint>) = size > MAX_PER_CHAT
                })
            }
            forChat[item.id] = Checkpoint(label, dir.path, message)
        } catch (e: Exception) {
            LOG.warn("Could not put a Local History label", e)
        }
    }

    /** Whether message [itemId] of chat [chatId] has a checkpoint its files can be reverted to. */
    fun hasCheckpoint(chatId: Int, itemId: String): Boolean = checkpoints[chatId]?.containsKey(itemId) == true
```

Replace `revert` with:

```kotlin
    override fun revert(chat: Chat, item: ChatItem.UserText, conversation: Boolean): RevertChoice? {
        val title = "Revert to Before This Message"
        val checkpoint = checkpoints[chat.id]?.get(item.id)
        if (checkpoint == null && !conversation) {
            Messages.showInfoMessage(project, "This message is too old to revert to.", title)
            return null
        }
        // A turn's edits in flight would land after the revert; the user stops it (and sees what it did) first.
        if (chat.busy) {
            Messages.showInfoMessage(project, "Claude is still working. Stop it, or wait for it to finish, then revert.", title)
            return null
        }
        val dir = checkpoint?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.dir) }
        val choices = RevertChoice.entries.filter { (!it.files || dir != null) && (!it.conversation || conversation) }
        val message = shorten(item.text)
        val text = buildString {
            append("Revert to before “$message”?\n\n")
            if (dir != null) {
                append("Files: every file in ${dir.presentableUrl} goes back to how it was then. This undoes Claude's ")
                append("changes from that message on, and your own changes since then too. You can undo it from Local History.\n\n")
            } else {
                append("The files can't be reverted: their folder isn't tracked by Local History, or the message is too old.\n\n")
            }
            if (conversation) append("Conversation: Claude forgets this message and everything after it, and the message goes back into the input box.")
        }
        val answer = Messages.showDialog(project, text, title, (choices.map { it.label } + "Cancel").toTypedArray(), 0, Messages.getWarningIcon())
        val choice = choices.getOrNull(answer) ?: return null
        if (choice.files && !revertFiles(chat, checkpoint!!, dir!!)) return null
        when (choice) {
            RevertChoice.FILES -> notes[chat.id] = "The user reverted the files in this checkout to how they were before their message " +
                "“$message”. Changes made since then are gone; re-read files before relying on what you remember of them."
            RevertChoice.CONVERSATION -> notes[chat.id] = "The user took the conversation back to before their message “$message”. " +
                "The files may still hold changes made after that point; re-read files before relying on what you remember of them."
            RevertChoice.FILES_AND_CONVERSATION -> notes.remove(chat.id)
        }
        return choice
    }

    /** Reverts [dir] to [checkpoint]'s label; false (with a notice in the chat) when Local History can't. */
    private fun revertFiles(chat: Chat, checkpoint: Checkpoint, dir: VirtualFile): Boolean {
        // Local History must have the latest state, Claude's last writes included, so the revert is complete and undoable.
        FileDocumentManager.getInstance().saveAllDocuments()
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        try {
            targets(dir).forEach { checkpoint.label.revert(project, it) }
        } catch (e: LocalHistoryException) {
            LOG.warn("Revert failed", e)
            chat.session.notice("Couldn't revert: ${e.message}")
            return false
        }
        chat.session.notice("Reverted the files to how they were before “${checkpoint.message}”.", isError = false)
        return true
    }
```

Add `import claudechat.session.RevertChoice`. `Messages.showDialog` returns the button index (Cancel and closing the dialog give an index past `choices`, or -1, so `getOrNull` returns null for both).

- [ ] **Step 5: Wording outside the code**

`chat.js:179` tooltip: `title: 'Revert the files, the conversation or both to before this message (asks first)'`.

`ChatBridgeTest.kt:32` comment: `/** What the bridge posted; appended off the EDT too (session events arrive on reader threads). */`

`README.md:51-54`, replace the bullet with:

```markdown
- **Revert to before a message.** Each message sent in this IDE session gets a **Revert to here** link (on hover).
  It asks what to revert: **Files and Conversation**, **Files Only** or **Conversation Only**. Files go back to how
  they were before that message went out, using the IDE's Local History (undoable from there; only files Local
  History tracks, so not excluded folders such as worktrees by default). The conversation goes back too: Claude
  forgets that message and everything after it, the chat continues in a fork of the session, and the message
  returns to the input box.
```

In the spec, replace item 6 of "Reverting the conversation" with:

```markdown
6. Notes to Claude with the next message: after **Files only**, that the files were reverted (as before); after
   **Conversation only**, that the files may still hold changes from the dropped turns; after **Files and
   conversation**, none, since Claude's memory and the files agree again.
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gw test`
Expected: all pass. If `ChatsTest` or `ChatBridgeTest` relied on `revertable` arriving later or on `Checkpoints.revert(chat, itemId)`, update them to the new signatures (find them with `grep -rn "revertable\|Checkpoints\|revertTo" src/test`).

- [ ] **Step 7: Run /simplify, then commit**

Run the `simplify` skill on the diff and apply what it finds, then:

```bash
git add -A src README.md docs/superpowers/specs/2026-11-03-rewind-conversation-design.md
git commit -m "Ask whether to revert the files, the conversation or both" -m "\"Revert to here\" now offers Files and Conversation, Files Only and Conversation Only. Reverting the conversation drops the message and everything after it from the chat, puts the message back in the input box, and continues in a fork of the session resumed at the answer before it. Messages in a checkout Local History doesn't track get the link too, offering only the conversation. Claude is told the files may hold later changes after a conversation-only revert, and nothing after both, since its memory and the files agree again."
```

---

### Task 5: Try it in WebStorm

This needs WebStorm restarted, which ends the chat running inside it, so the user does it.

- [ ] **Step 1:** Install: `./install.sh --force`, then restart WebStorm.
- [ ] **Step 2:** In a new chat, send two messages that each edit a file. On the second, choose **Revert to here → Files and Conversation**. Expected: the second message and its turn leave the panel, its text is in the input box, the file is back to the first turn's version, and asking "what did I ask you last?" gets the first message.
- [ ] **Step 3:** Rewind twice in a row: after Step 2, send a new second message, then **Revert to here → Conversation Only** on it. Expected: the same as Step 2 (a rewind of a fork resumes at the original message's uuid). Then rewind the first message and send: a fresh session that remembers nothing.
- [ ] **Step 4:** Rename the chat, rewind again, restart WebStorm, and resume it from history. Expected: the rewound chat is listed under its name and shows only the kept messages; the original session is listed too.
