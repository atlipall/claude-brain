# Subagent Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show each Agent/Task call as an expandable card that holds the subagent's own messages, tool rows and report.

**Architecture:** The parser tags subagent events with `parentId` (their `parent_tool_use_id`) and turns `system/task_*`
events into typed events. The session routes tagged events into items with `parentId` (without touching the main turn's
phase) and keeps an `AgentInfo` on the Agent call's `ToolCall`. The page renders Agent/Task calls as cards with a
persistent child container and places items with a `parentId` inside it.

**Tech Stack:** Kotlin (kotlinx.serialization JSON), JUnit 4, plain JS/CSS webview (marked + DOMPurify).

**Spec:** `docs/superpowers/specs/2026-11-03-subagent-cards-design.md`

## Global Constraints

- Report and prompt: at most 100 000 characters kept; longer is cut and ends with "…".
- `SubagentHandback` calls from a subagent are never shown.
- Subagent events never change the main turn's phase or `runningTools`.
- Model text in the page goes in only as text nodes or through `marked` + `DOMPurify.sanitize(…, SANITIZE)`.
- Run tests with `./gw test` (the wrapper uses WebStorm's JBR).

---

### Task 1: Parse subagent events and task events

**Files:**
- Modify: `src/main/kotlin/claudechat/cli/CliEvent.kt`
- Test: `src/test/kotlin/claudechat/cli/CliParserTest.kt`, fixtures in `src/test/kotlin/claudechat/testutil/Fx.kt`

**Interfaces:**
- Produces:
  - `CliEvent.AssistantText(messageId, text, parentId: String? = null)`
  - `CliEvent.ToolUse(toolUseId, name, input, parentId: String? = null)`
  - `CliEvent.ToolResult(…, images, parentId: String? = null)` (new last parameter)
  - `CliEvent.TaskStarted(toolUseId: String, subagentType: String?, background: Boolean, prompt: String?)`
  - `CliEvent.TaskProgress(toolUseId: String, toolUses: Int?, durationMs: Long?, lastTool: String?)`
  - `CliEvent.TaskDone(toolUseId: String, status: String, toolUses: Int?, durationMs: Long?, report: String?)`
  - A `user` message with a `parent_tool_use_id` yields no `UserText`; stream events with one are `Ignored("stream_event/subagent")`.

- [ ] **Step 1: Fixtures.** Add to `Fx` (shapes copied from the recorded CLI 2.1.317 output):

```kotlin
    fun subagentToolUse(parent: String, toolUseId: String, name: String, inputJson: String) =
        """{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"sm-$toolUseId","type":"message","role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"$name","input":$inputJson}],"stop_reason":null},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u20"}"""

    fun subagentText(parent: String, msg: String, text: String) =
        """{"type":"assistant","message":{"model":"claude-haiku-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":null},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u21"}"""

    fun subagentPrompt(parent: String, text: String) =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${q(text)}}]},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u22"}"""

    fun subagentToolResult(parent: String, toolUseId: String, content: String) =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$toolUseId","type":"tool_result","content":${q(content)}}]},"parent_tool_use_id":"$parent","session_id":"sess-1","uuid":"u23"}"""

    fun subagentTextDelta(parent: String, msg: String, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${q(text)}}},"session_id":"sess-1","parent_tool_use_id":"$parent","uuid":"u24","api_message_id":"$msg"}"""

    fun taskStarted(toolUseId: String, background: Boolean = false, prompt: String = "Do it") =
        """{"type":"system","subtype":"task_started","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","description":"Do it","subagent_type":"general-purpose","is_backgrounded":$background,"spawn_depth":1,"task_type":"local_agent","prompt":${q(prompt)},"uuid":"u25","session_id":"sess-1"}"""

    fun taskProgress(toolUseId: String, toolUses: Int, durationMs: Long, lastTool: String) =
        """{"type":"system","subtype":"task_progress","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","description":"Do it","subagent_type":"general-purpose","usage":{"total_tokens":100,"tool_uses":$toolUses,"duration_ms":$durationMs},"last_tool_name":"$lastTool","uuid":"u26","session_id":"sess-1"}"""

    fun taskNotification(toolUseId: String, status: String = "completed", report: String? = "DONE") =
        """{"type":"system","subtype":"task_notification","task_id":"a1","run_id":"r1","tool_use_id":"$toolUseId","status":"$status","output_file":"/tmp/x.output","summary":"s","usage":{"total_tokens":120,"tool_uses":3,"duration_ms":4063},"handback":"send"${if (report != null) ""","handback_report":{"text":${q(report)}}""" else ""},"uuid":"u27","session_id":"sess-1"}"""
```

- [ ] **Step 2: Failing tests** in `CliParserTest`:

```kotlin
    @Test fun subagentEventsCarryTheirParent() {
        assertEquals(CliEvent.ToolUse("t2", "Bash", parseJsonObject("""{"command":"ls"}""")!!, "t1"),
            one(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}""")))
        assertEquals(CliEvent.AssistantText("sm1", "hi", "t1"), one(Fx.subagentText("t1", "sm1", "hi")))
        assertEquals("t1", (one(Fx.subagentToolResult("t1", "t2", "out")) as CliEvent.ToolResult).parentId)
        assertTrue(CliParser.parse(Fx.subagentPrompt("t1", "Do it")).isEmpty())
        assertEquals(CliEvent.Ignored("stream_event/subagent"), one(Fx.subagentTextDelta("t1", "sm1", "x")))
        assertNull((one(Fx.toolUse("m1", "t1", "Agent", "{}")) as CliEvent.ToolUse).parentId)
    }

    @Test fun taskEventsAreParsed() {
        assertEquals(CliEvent.TaskStarted("t1", "general-purpose", true, "Count"), one(Fx.taskStarted("t1", true, "Count")))
        assertEquals(CliEvent.TaskProgress("t1", 7, 42000, "Bash"), one(Fx.taskProgress("t1", 7, 42000, "Bash")))
        assertEquals(CliEvent.TaskDone("t1", "completed", 3, 4063, "DONE"), one(Fx.taskNotification("t1")))
        assertEquals(CliEvent.TaskDone("t1", "failed", 3, 4063, null), one(Fx.taskNotification("t1", "failed", null)))
    }
```

(Add `import claudechat.cli.parseJsonObject` / `assertNull` / `assertTrue` if the file lacks them.)

- [ ] **Step 3: Run** `./gw test --tests 'claudechat.cli.CliParserTest'` — expect compile failure (no `parentId`, no task events).

- [ ] **Step 4: Implement** in `CliEvent.kt`:

```kotlin
    data class AssistantText(val messageId: String, val text: String, val parentId: String? = null) : CliEvent
    data class ToolUse(val toolUseId: String, val name: String, val input: JsonObject, val parentId: String? = null) : CliEvent
    // ToolResult: add as the last parameter
        /** The Agent call this result belongs to when it comes from inside a subagent. */
        val parentId: String? = null,
    /** A subagent began; [toolUseId] is the Agent call that started it. */
    data class TaskStarted(val toolUseId: String, val subagentType: String?, val background: Boolean, val prompt: String?) : CliEvent
    data class TaskProgress(val toolUseId: String, val toolUses: Int?, val durationMs: Long?, val lastTool: String?) : CliEvent
    /** A subagent ended; [status] is "completed" on success. [report] is what it handed back. */
    data class TaskDone(val toolUseId: String, val status: String, val toolUses: Int?, val durationMs: Long?, val report: String?) : CliEvent
```

In `parse`, the `system` branch gains:

```kotlin
                    "task_started" -> listOf(CliEvent.TaskStarted(obj.str("tool_use_id") ?: "", obj.str("subagent_type"),
                        obj.bool("is_backgrounded") == true, obj.str("prompt")))
                    "task_progress" -> listOf(CliEvent.TaskProgress(obj.str("tool_use_id") ?: "",
                        obj.obj("usage")?.long("tool_uses")?.toInt(), obj.obj("usage")?.long("duration_ms"), obj.str("last_tool_name")))
                    "task_notification" -> listOf(CliEvent.TaskDone(obj.str("tool_use_id") ?: "", obj.str("status") ?: "",
                        obj.obj("usage")?.long("tool_uses")?.toInt(), obj.obj("usage")?.long("duration_ms"),
                        obj.obj("handback_report")?.str("text")))
```

`streamEvent`: first line after reading `messageId`:
`if (obj.str("parent_tool_use_id") != null) return CliEvent.Ignored("stream_event/subagent")`, and the `message_start`
branch becomes `"message_start" -> CliEvent.MessageStart(messageId)`.

`assistant(obj)`: `val parent = obj.str("parent_tool_use_id")`, passed as `parentId` to `AssistantText` and `ToolUse`.

`user(obj)`: `val parent = obj.str("parent_tool_use_id")`; a string content with a parent returns `emptyList()`;
the `"text"` block maps to `null` when `parent != null`; `ToolResult(…, parentId = parent)`.

Update the existing `otherSystemMessagesAreIgnored` assertion for `subagentMessageStart` to expect
`CliEvent.Ignored("stream_event/subagent")`.

- [ ] **Step 5: Run** `./gw test` — all pass.
- [ ] **Step 6: Commit** `git commit -am "Parse subagent events and task events"`

---

### Task 2: Items carry a parent and agent info

**Files:**
- Modify: `src/main/kotlin/claudechat/session/ChatItem.kt`
- Test: `src/test/kotlin/claudechat/session/ChatItemTest.kt`

**Interfaces:**
- Produces:
  - `enum class AgentState { RUNNING, DONE, FAILED }`
  - `data class AgentInfo(val subagentType: String?, val background: Boolean, val prompt: String?, val state: AgentState, val toolUses: Int? = null, val durationMs: Long? = null, val lastTool: String? = null, val report: String? = null)`
  - `ChatItem.AssistantText(id, text, streaming, parentId: String? = null)`
  - `ChatItem.ToolCall(…, question, parentId: String? = null, agent: AgentInfo? = null)`
  - JSON: `"parentId"` only when set; `"agent":{"subagentType","background","prompt","state":"running|done|failed","toolUses","durationMs","lastTool","report"}` only when set.

- [ ] **Step 1: Failing tests**

```kotlin
    @Test fun subagentTextHasItsParent() = assertEquals(
        """{"id":"a1","kind":"assistant","text":"x","streaming":false,"parentId":"t1"}""",
        ChatItem.AssistantText("a1", "x", false, "t1").toJson().toString())

    @Test fun agentCall() = assertEquals(
        """{"id":"t1","kind":"tool","name":"Agent","input":{},"filePath":null,"status":"done","permissionRequestId":null,"resultPreview":null,"hasDiff":false,""" +
            """"agent":{"subagentType":"general-purpose","background":true,"prompt":"p","state":"running","toolUses":2,"durationMs":900,"lastTool":"Bash","report":null}}""",
        ChatItem.ToolCall("t1", "Agent", buildJsonObject {}, null, ToolStatus.DONE, null, null, false,
            agent = AgentInfo("general-purpose", true, "p", AgentState.RUNNING, 2, 900, "Bash")).toJson().toString())

    @Test fun subagentToolHasItsParent() = assertEquals(
        """{"id":"t2","kind":"tool","name":"Bash","input":{},"filePath":null,"status":"running","permissionRequestId":null,"resultPreview":null,"hasDiff":false,"parentId":"t1"}""",
        ChatItem.ToolCall("t2", "Bash", buildJsonObject {}, null, ToolStatus.RUNNING, null, null, false, parentId = "t1").toJson().toString())
```

- [ ] **Step 2: Run** `./gw test --tests 'claudechat.session.ChatItemTest'` — compile failure.

- [ ] **Step 3: Implement.** Add the types and fields above. In `toJson`: for `AssistantText` append
`item.parentId?.let { put("parentId", it) }`; for `ToolCall`, after `question`:

```kotlin
            item.parentId?.let { put("parentId", it) }
            item.agent?.let { a ->
                put("agent", buildJsonObject {
                    put("subagentType", a.subagentType)
                    put("background", a.background)
                    put("prompt", a.prompt)
                    put("state", a.state.name.lowercase())
                    put("toolUses", a.toolUses)
                    put("durationMs", a.durationMs)
                    put("lastTool", a.lastTool)
                    put("report", a.report)
                })
            }
```

- [ ] **Step 4: Run** `./gw test` — all pass.
- [ ] **Step 5: Commit** `git commit -am "Chat items carry a parent and agent info"`

---

### Task 3: The session routes subagent events into the card

**Files:**
- Modify: `src/main/kotlin/claudechat/session/ChatSession.kt`
- Test: `src/test/kotlin/claudechat/session/ChatSessionTest.kt`

**Interfaces:**
- Consumes: Task 1 events, Task 2 types.
- Produces: items as the page needs them — subagent `AssistantText`/`ToolCall` with `parentId`; the Agent `ToolCall` with `agent`.

- [ ] **Step 1: Failing tests**

```kotlin
    // --- subagents ---

    private fun startAgent(s: ChatSession, background: Boolean = false) {
        s.send("go")
        conn.emit(Fx.toolUse("m1", "t1", "Agent", """{"description":"Do it","subagent_type":"general-purpose","prompt":"Do it"}"""))
        conn.emit(Fx.taskStarted("t1", background, "Do it"))
    }

    @Test fun anAgentCallCarriesItsAgentInfo() {
        val s = session()
        startAgent(s, background = true)
        conn.emit(Fx.taskProgress("t1", 2, 900, "Bash"))
        val agent = listener.latest<ChatItem.ToolCall>().agent!!
        assertEquals(AgentInfo("general-purpose", true, "Do it", AgentState.RUNNING, 2, 900, "Bash"), agent)
        conn.emit(Fx.taskNotification("t1", report = "All done"))
        val done = listener.latest<ChatItem.ToolCall>().agent!!
        assertEquals(AgentState.DONE, done.state)
        assertEquals("All done", done.report)
        assertEquals(3, done.toolUses)
        assertNull(done.lastTool)
    }

    @Test fun aFailedAgentIsFailed() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.taskNotification("t1", "failed", null))
        assertEquals(AgentState.FAILED, listener.latest<ChatItem.ToolCall>().agent!!.state)
    }

    @Test fun subagentItemsCarryTheirParentAndLeaveThePhaseAlone() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentText("t1", "sm1", "Looking"))
        conn.emit(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}"""))
        assertEquals(Activity.Running("Agent"), listener.activities.last())
        conn.emit(Fx.subagentToolResult("t1", "t2", "a.txt"))
        assertEquals(Activity.Running("Agent"), listener.activities.last())
        assertEquals("t1", listener.latest<ChatItem.AssistantText>().parentId)
        val bash = listener.latest<ChatItem.ToolCall>()
        assertEquals("t2", bash.id)
        assertEquals("t1", bash.parentId)
        assertEquals(ToolStatus.DONE, bash.status)
    }

    @Test fun handbackCallsAreNotShown() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentToolUse("t1", "t3", "SubagentHandback", """{"text":"x"}"""), Fx.subagentToolResult("t1", "t3", "ok"))
        assertTrue(listener.items.none { it.id == "t3" })
    }

    @Test fun aSubagentPermissionRequestKeepsItsParent() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.subagentToolUse("t1", "t2", "Bash", """{"command":"ls"}"""))
        conn.emit(Fx.canUseTool("r1", "t2", "Bash", """{"command":"ls"}"""))
        val bash = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, bash.status)
        assertEquals("t1", bash.parentId)
    }

    @Test fun longReportsAreCut() {
        val s = session()
        startAgent(s)
        conn.emit(Fx.taskNotification("t1", report = "x".repeat(200_000)))
        val report = listener.latest<ChatItem.ToolCall>().agent!!.report!!
        assertEquals(100_001, report.length)
        assertTrue(report.endsWith("…"))
    }
```

(If `RecordingListener` has no `items` list, use whatever it records items in — check `testutil` — e.g. `listener.all<ChatItem.ToolCall>()`.)

- [ ] **Step 2: Run** `./gw test --tests 'claudechat.session.ChatSessionTest'` — failures.

- [ ] **Step 3: Implement** in `ChatSession`:

```kotlin
/** Longest agent prompt or report kept; longer ones are cut and end with "…". */
internal const val MAX_AGENT_TEXT = 100_000
private const val HANDBACK = "SubagentHandback"
private fun capped(text: String?) = text?.let { if (it.length > MAX_AGENT_TEXT) it.take(MAX_AGENT_TEXT) + "…" else it }

    /** What is known about each subagent, by the Agent call's tool_use_id (task events may precede the call's item). */
    private val agents = HashMap<String, AgentInfo>()

    private fun updateAgent(toolUseId: String, change: (AgentInfo) -> AgentInfo) {
        val info = change(agents[toolUseId] ?: AgentInfo(null, false, null, AgentState.RUNNING))
        agents[toolUseId] = info
        toolCalls[toolUseId]?.let { upsertTool(it.copy(agent = info)) }
    }
```

In `handle`:

```kotlin
            is CliEvent.AssistantText -> when {
                event.parentId != null -> emit(ChatItem.AssistantText(nextId("text"), event.text, false, event.parentId))
                event.messageId !in streamedMessages -> emit(ChatItem.AssistantText(nextId("text"), event.text, false))
            }
            is CliEvent.ToolUse -> {
                if (event.parentId != null && event.name == HANDBACK) return
                val base = toolCalls[event.toolUseId]
                    ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
                upsertTool(base.copy(name = event.name, input = event.input, filePath = filePathOf(event.input),
                    parentId = event.parentId ?: base.parentId, agent = agents[event.toolUseId] ?: base.agent))
                if (event.parentId == null) {
                    runningTools[event.toolUseId] = event.name
                    phase = Activity.Running(event.name)
                    reportActivity()
                }
            }
            is CliEvent.TaskStarted -> updateAgent(event.toolUseId) {
                it.copy(subagentType = event.subagentType, background = event.background, prompt = capped(event.prompt))
            }
            is CliEvent.TaskProgress -> updateAgent(event.toolUseId) {
                it.copy(toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs, lastTool = event.lastTool)
            }
            is CliEvent.TaskDone -> updateAgent(event.toolUseId) {
                it.copy(state = if (event.status == "completed") AgentState.DONE else AgentState.FAILED,
                    toolUses = event.toolUses ?: it.toolUses, durationMs = event.durationMs ?: it.durationMs,
                    lastTool = null, report = capped(event.report) ?: it.report)
            }
```

`onToolResult`: wrap the first three lines in `if (event.parentId == null) { … }`.
`handleExit`: `agents.clear()` is NOT done (cards of a dead process keep their info; the map is small).

- [ ] **Step 4: Run** `./gw test` — all pass.
- [ ] **Step 5: Commit** `git commit -am "Route subagent events into their Agent call"`

---

### Task 4: Cards in the page

**Files:**
- Modify: `src/main/resources/webview/chat.js`, `src/main/resources/webview/chat.css`

**Interfaces:**
- Consumes: item JSON from Task 2 (`parentId`, `agent`).

- [ ] **Step 1: Markdown helper.** Replace `renderAssistant`'s body with a shared helper:

```js
  // Model markdown into el: parsed, sanitized, images as markers until the IDE resolves them.
  function renderMarkdown(el, text, streaming) {
    imgSlots = [];
    let html, slots;
    try { html = marked.parse(text || ''); } finally { slots = imgSlots; imgSlots = null; }
    el.innerHTML = DOMPurify.sanitize(html, SANITIZE);
    const images = placeImages(el, slots);
    if (!streaming) { linkify(el); resolveImages(images); }
  }

  function renderAssistant(el, item) {
    el.className = 'msg assistant';
    renderMarkdown(el, item.text, item.streaming);
  }
```

- [ ] **Step 2: Placement.** Next to `items`:

```js
  // Subagent cards: an Agent/Task call is a card with a container that survives its re-renders; items with a parentId
  // go in their card's container, or wait until the card is shown.
  const childBoxes = new Map();   // card id -> container of its items
  const waitingFor = new Map();   // card id -> elements waiting for it
  const parentOf = new Map();     // item id -> its card id
  const expandedAgents = new Set();
  const isAgentCall = (item) => item.kind === 'tool' && (item.name === 'Agent' || item.name === 'Task');

  function childBox(id) {
    let box = childBoxes.get(id);
    if (!box) { box = h('div', { class: 'agent-children' }); childBoxes.set(id, box); }
    return box;
  }

  function place(el, parentId) {
    const box = parentId ? (items.has(parentId) && childBoxes.get(parentId)) : log;
    if (!box) {
      el.remove();
      const list = waitingFor.get(parentId) || [];
      if (!list.includes(el)) list.push(el);
      waitingFor.set(parentId, list);
      return;
    }
    if (el.parentNode !== box) box.appendChild(el);
  }
```

`upsert` becomes:

```js
  function upsert(item) {
    const atBottom = nearBottom(log.clientHeight);
    let el = items.get(item.id);
    if (!el) { el = document.createElement('div'); items.set(item.id, el); }
    const parentId = typeof item.parentId === 'string' ? item.parentId : null;
    if (parentId) parentOf.set(item.id, parentId); else parentOf.delete(item.id);
    place(el, parentId);
    // <details> inside a card's children belong to those items, not to this one.
    const own = [...el.querySelectorAll('details')].filter((d) => { const c = d.closest('.agent-children'); return !c || !el.contains(c); });
    const open = own.map((d) => d.open);
    el.textContent = '';
    switch (item.kind) {
      case 'user': renderUser(el, item); break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': if (isAgentCall(item)) renderAgent(el, item); else renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.textContent = item.text; break;
    }
    [...el.querySelectorAll('details')].filter((d) => { const c = d.closest('.agent-children'); return !c || !el.contains(c); })
      .forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    if (item.kind === 'tool' && item.status === 'awaiting_permission') {
      for (let p = parentId; p; p = parentOf.get(p)) setAgentExpanded(p, true);
    }
    if (atBottom) log.scrollTop = log.scrollHeight;
  }
```

(Extract the `own` filter into `const ownDetails = (el) => …` to avoid writing it twice.)

`reset` also clears `childBoxes`, `waitingFor` and `parentOf`.

- [ ] **Step 3: The card.**

```js
  const AGENT_ICON = { done: '✓', failed: '✗' };

  // running | done | failed: from the task events, else (older CLI) from the call's own status.
  function agentState(t) {
    const s = t.agent && t.agent.state;
    if (s === 'running' || s === 'done' || s === 'failed') return s;
    if (t.status === 'done') return 'done';
    return t.status === 'running' || t.status === 'awaiting_permission' ? 'running' : 'failed';
  }

  function agentSide(a, state) {
    if (!a) return '';
    const parts = [];
    if (state === 'running' && typeof a.lastTool === 'string' && a.lastTool) parts.push(a.lastTool.slice(0, 40));
    if (Number.isInteger(a.toolUses)) parts.push(a.toolUses + (a.toolUses === 1 ? ' tool' : ' tools'));
    if (Number.isFinite(a.durationMs)) parts.push(Math.round(a.durationMs / 1000) + 's');
    return parts.join(' · ');
  }

  function renderAgent(el, t) {
    const a = t.agent && typeof t.agent === 'object' ? t.agent : null;
    const state = agentState(t);
    const open = expandedAgents.has(t.id);
    el.className = 'msg agent agent-' + state;
    const desc = toolDescription(t);
    el.append(h('div', { class: 'agent-head', role: 'button', tabindex: '0', 'data-id': t.id, 'aria-expanded': open ? 'true' : 'false' },
      h('span', { class: 'tool-icon' }, state === 'running' ? h('span', { class: 'spin' }) : AGENT_ICON[state]),
      h('span', { class: 'tool-name' }, t.name),
      h('span', { class: 'tool-desc', title: desc || null }, desc),
      a && typeof a.subagentType === 'string' && a.subagentType ? h('span', { class: 'agent-tag' }, a.subagentType.slice(0, 40)) : null,
      a && a.background === true ? h('span', { class: 'agent-tag' }, 'background') : null,
      h('span', { class: 'tool-side' }, agentSide(a, state)),
      h('span', { class: 'tool-toggle' }, open ? '▾' : '▸')));
    const body = h('div', { class: 'agent-body', hidden: !open });
    if (a && typeof a.prompt === 'string' && a.prompt) body.append(h('details', {}, h('summary', {}, 'Prompt'), h('pre', {}, clip(a.prompt))));
    body.append(childBox(t.id));
    if (a && typeof a.report === 'string' && a.report) {
      const report = h('div', { class: 'agent-report' });
      renderMarkdown(report, a.report, false);
      body.append(h('div', { class: 'tool-label' }, 'Report'), report);
    } else if (!a && t.resultPreview) {
      body.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, clip(t.resultPreview))));
    }
    el.append(body);
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    const waiting = waitingFor.get(t.id);
    if (waiting) { waitingFor.delete(t.id); waiting.forEach((c) => childBox(t.id).appendChild(c)); }
  }

  function setAgentExpanded(id, open) {
    if (open) expandedAgents.add(id); else expandedAgents.delete(id);
    const el = items.get(id);
    if (!el) return;
    const head = el.querySelector(':scope > .agent-head');
    const body = el.querySelector(':scope > .agent-body');
    if (head) {
      head.setAttribute('aria-expanded', open ? 'true' : 'false');
      const toggle = head.querySelector('.tool-toggle');
      if (toggle) toggle.textContent = open ? '▾' : '▸';
    }
    if (body) body.hidden = !open;
  }
```

- [ ] **Step 4: Clicks and keys.** In the document click handler, in the `if (!b)` branch before the `.tool-row` lookup:

```js
      const head = e.target.closest('.agent-head');
      if (head && trusted.has(head) && !(window.getSelection && window.getSelection().toString())) {
        setAgentExpanded(head.dataset.id, head.getAttribute('aria-expanded') !== 'true');
        return;
      }
```

In the keydown handler, before the `.tool-row` lookup:

```js
    const head = e.target.closest && e.target.closest('.agent-head');
    if (head && e.target === head && trusted.has(head)) { e.preventDefault(); setAgentExpanded(head.dataset.id, head.getAttribute('aria-expanded') !== 'true'); return; }
```

- [ ] **Step 5: CSS** (append to `chat.css` after the `.tool-*` rules):

```css
.agent { border-left: 3px solid var(--border); padding-left: 4px; }
.agent.agent-running { border-left-color: var(--accent); }
.agent.agent-failed { border-left-color: var(--danger); }
.agent-done > .agent-head .tool-icon { color: var(--ok); }
.agent-failed > .agent-head .tool-icon { color: var(--danger); }
.agent-head { display: flex; align-items: baseline; gap: 8px; padding: 2px 4px; border-radius: 4px; color: var(--muted); cursor: pointer; }
.agent-head:hover { background: var(--code-bg); }
.agent-head .tool-name { color: var(--fg); flex: none; }
.agent-tag { flex: none; font-size: 11px; border: 1px solid var(--border); border-radius: 8px; padding: 0 6px; }
.agent-body { margin: 2px 0 4px 18px; }
.agent-children > .msg { margin: 4px 0; }
.agent-report { border-top: 1px solid var(--border); margin-top: 4px; }
```

- [ ] **Step 6: Check.** `node --check src/main/resources/webview/chat.js`, `./gw test` (WebAssetsTest inlines the page),
then a headless smoke test: load the built page (`WebAssets.page()` written to a temp file) in headless Chrome, call
`window.chat.receive(...)` with: an Agent call, task info, a subagent text, a subagent Bash row (permission pending),
a child that arrives before its card; check the card shows, opens on the permission request, children sit inside it,
and the waiting child moves in once the card exists.

- [ ] **Step 7: Commit** `git commit -am "Show subagents as expandable cards"`
