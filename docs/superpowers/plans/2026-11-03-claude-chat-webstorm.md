# Claude Chat for WebStorm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A WebStorm plugin with a native chat tool window that drives the locally installed, Pro-logged-in `claude` CLI, links files Claude touches, shows diffs, and tells Claude about files the user edited.

**Architecture:** Kotlin IntelliJ Platform plugin. A `ChatSession` talks NDJSON to one `claude -p --input-format stream-json --output-format stream-json …` process through a `CliConnection` interface (real: `ClaudeProcess`; tests: `FakeConnection`). A project-level `SessionManager` owns the active session and forwards `ChatItem`s to a JCEF web view via `ChatBridge`. IDE-side services `EditTracker` and `FileLinker` provide edit notes, file navigation and diffs.

**Tech Stack:** Kotlin 2.4.21, Gradle 9.8.2 wrapper, IntelliJ Platform Gradle Plugin 2.19.0, WebStorm 2026.2.3 (build 262) via `local(...)`, JBR 25, platform-bundled `kotlinx.serialization.json` (element API only), JUnit 4, JCEF, vendored `marked@15.0.12` and `dompurify@3.2.6`.

**Spec:** `docs/superpowers/specs/2026-11-03-claude-chat-webstorm-design.md`

## Global Constraints

- Personal use only; never read, store or forward Claude credentials. Authentication is the CLI's job.
- Target: locally installed WebStorm at `/Applications/WebStorm.app` (2026.2.3, build 262.10968.77); `sinceBuild = "262"`.
- JDK: `/Applications/WebStorm.app/Contents/jbr/Contents/Home` (JBR 25). No system Java exists; always run Gradle through `./gw` (created in Task 1), which sets `JAVA_HOME`.
- Kotlin package root: `claudechat`. Plugin id: `local.claudechat`. Tool window id: `Claude Chat`.
- JSON: only `kotlinx.serialization.json` element API (`Json.parseToJsonElement`, `JsonObject`, `buildJsonObject`). Do not add the serialization compiler plugin or any JSON dependency.
- CLI command (verified against claude 2.1.317): `claude -p --input-format stream-json --output-format stream-json --include-partial-messages --verbose --permission-prompt-tool stdio [--permission-mode <mode>] [--resume <id>] [extra args]`.
- Permission modes offered: `default`, `acceptEdits`, `plan`, `auto`.
- A resumed CLI starts in `default` mode, so restart/resume must pass the current mode.
- Sessions live at `~/.claude/projects/<cwd with every [^A-Za-z0-9] replaced by '-'>/<sessionId>.jsonl` (`CLAUDE_CONFIG_DIR` overrides `~/.claude`).
- Model output is rendered only after `DOMPurify.sanitize`; the page never navigates (all link clicks are intercepted).
- Tests never call the real Claude API.

## Verified protocol shapes (from probing claude 2.1.317)

Outgoing (stdin, one JSON per line):
```
{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize","hooks":null}}
{"type":"user","message":{"role":"user","content":"<text>"}}
{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"behavior":"allow","updatedInput":{...original input...}}}}
{"type":"control_response","response":{"subtype":"success","request_id":"<id>","response":{"behavior":"deny","message":"<reason>"}}}
{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}
{"type":"control_request","request_id":"<id>","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}
```
Incoming (stdout) that matters: `system/init` (`session_id`, `permissionMode`; sent at the start of every turn), `stream_event` with `event.type` `content_block_delta`/`text_delta` and `content_block_stop` (keyed by `api_message_id` + `event.index`), `assistant` (one content block per line: `text`, `tool_use`, `thinking`), `user` (`tool_result` blocks, plus top-level `tool_use_result` which for Edit/Write contains `filePath` and `originalFile`, `null` for a newly created file), `control_request` `can_use_tool` (`tool_name`, `input`, `tool_use_id`), `control_response` (init response has `current_permission_mode`; set-mode response has `mode`), `result` (`subtype`, `is_error`, `result`, `duration_ms`, `total_cost_usd`, `errors`). Interrupt produces a `user` text `[Request interrupted by user]` and a `result` with `subtype: error_during_execution`. Everything else (`rate_limit_event`, hook `system` messages, `message_start`, …) is ignored.

## File structure

```
gw                                              Gradle launcher that sets JAVA_HOME
settings.gradle.kts, build.gradle.kts, gradle.properties, .gitignore
src/main/resources/META-INF/plugin.xml
src/main/resources/webview/index.html            page skeleton with INLINE markers
src/main/resources/webview/chat.css
src/main/resources/webview/chat.js               all UI behaviour
src/main/resources/webview/vendor/marked.min.js  vendored, pinned
src/main/resources/webview/vendor/purify.min.js  vendored, pinned
src/main/kotlin/claudechat/cli/JsonExt.kt        tiny JsonObject accessors
src/main/kotlin/claudechat/cli/CliEvent.kt       incoming event types + CliParser
src/main/kotlin/claudechat/cli/CliMessages.kt    outgoing message builders
src/main/kotlin/claudechat/cli/ClaudeCommand.kt  PermissionMode, SessionLaunch, argv builder, binary resolution
src/main/kotlin/claudechat/cli/CliConnection.kt  transport interface
src/main/kotlin/claudechat/cli/ClaudeProcess.kt  real transport (ProcessBuilder)
src/main/kotlin/claudechat/session/ChatItem.kt   UI model + JSON
src/main/kotlin/claudechat/session/ChatSession.kt conversation state machine
src/main/kotlin/claudechat/session/SessionStore.kt past sessions + transcripts
src/main/kotlin/claudechat/session/SessionManager.kt project service, SessionView
src/main/kotlin/claudechat/ide/EditTracker.kt
src/main/kotlin/claudechat/ide/FileLinker.kt
src/main/kotlin/claudechat/ide/AddToChatAction.kt
src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt
src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt
src/main/kotlin/claudechat/ui/WebAssets.kt
src/main/kotlin/claudechat/ui/Theme.kt
src/main/kotlin/claudechat/ui/ChatBridge.kt
src/main/kotlin/claudechat/ui/ChatPanel.kt
src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt
src/test/kotlin/claudechat/testutil/Fx.kt        realistic CLI line builders
src/test/kotlin/claudechat/testutil/FakeConnection.kt
src/test/kotlin/claudechat/... tests mirroring main
```

---

### Task 1: Project scaffold

**Files:**
- Create: `gw`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `src/main/resources/META-INF/plugin.xml`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- Test: `src/test/kotlin/claudechat/SmokeTest.kt`

**Interfaces:**
- Produces: a building plugin project; `./gw test` runs JUnit 4 and `BasePlatformTestCase` tests.

- [ ] **Step 1: Generate the Gradle wrapper with a throwaway Gradle distribution**

```bash
cd /Users/atli/projects/plugin
TMP=$(mktemp -d)
curl -fsSL https://services.gradle.org/distributions/gradle-9.8.2-bin.zip -o "$TMP/g.zip"
unzip -q "$TMP/g.zip" -d "$TMP"
echo 'rootProject.name = "claude-chat"' > settings.gradle.kts
JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home "$TMP/gradle-9.8.2/bin/gradle" wrapper --gradle-version 9.8.2
rm -rf "$TMP"
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` exist.

- [ ] **Step 2: Create `gw`**

```sh
#!/bin/sh
# Runs the Gradle wrapper with WebStorm's bundled JBR (no system Java on this machine).
export JAVA_HOME="${JAVA_HOME:-/Applications/WebStorm.app/Contents/jbr/Contents/Home}"
exec "$(dirname "$0")/gradlew" "$@"
```
Run: `chmod +x gw`

- [ ] **Step 3: Create build files**

`gradle.properties`:
```properties
org.gradle.java.installations.paths=/Applications/WebStorm.app/Contents/jbr/Contents/Home
org.gradle.java.installations.auto-download=false
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
```

`build.gradle.kts`:
```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.claudechat"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/WebStorm.app")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
```

`.gitignore`:
```
.gradle/
build/
.intellijPlatform/
.kotlin/
.idea/
*.iml
```

`src/main/resources/META-INF/plugin.xml`:
```xml
<idea-plugin>
    <id>local.claudechat</id>
    <name>Claude Chat</name>
    <vendor>local</vendor>
    <description><![CDATA[
    Native chat panel for the Claude Code CLI. Runs the locally installed <code>claude</code>
    binary with your existing login, links files Claude touches, shows diffs of its edits,
    and tells Claude about files you edited between turns.
    ]]></description>
    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
    </extensions>
</idea-plugin>
```

- [ ] **Step 4: Write a smoke test**

`src/test/kotlin/claudechat/SmokeTest.kt`:
```kotlin
package claudechat

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SmokeTest : BasePlatformTestCase() {
    fun testLightProjectLoads() {
        assertNotNull(project.basePath)
    }
}
```

- [ ] **Step 5: Run the build and tests**

Run: `./gw test buildPlugin`
Expected: `BUILD SUCCESSFUL`; `build/distributions/claude-chat-0.1.0.zip` exists; 1 test passed.
If the test framework artifact for build `262.10968.77` cannot be resolved, replace `local("/Applications/WebStorm.app")` with `webstorm("2026.2.3")` and rerun (downloads the IDE once).
If Kotlin reports it cannot target JVM 25, change `jvmToolchain(25)` to `jvmToolchain(21)` and set `org.gradle.java.installations.auto-download=true` in `gradle.properties` plus add `id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"` in a `plugins {}` block at the top of `settings.gradle.kts`.

- [ ] **Step 6: Commit**

```bash
git add gw gradlew gradlew.bat gradle settings.gradle.kts build.gradle.kts gradle.properties .gitignore src
git commit -m "Scaffold IntelliJ Platform plugin project"
```

---

### Task 2: CLI protocol parsing and outgoing messages

**Files:**
- Create: `src/main/kotlin/claudechat/cli/JsonExt.kt`, `src/main/kotlin/claudechat/cli/CliEvent.kt`, `src/main/kotlin/claudechat/cli/CliMessages.kt`
- Create: `src/test/kotlin/claudechat/testutil/Fx.kt`
- Test: `src/test/kotlin/claudechat/cli/CliParserTest.kt`, `src/test/kotlin/claudechat/cli/CliMessagesTest.kt`

**Interfaces:**
- Produces:
  - `fun parseJsonObject(line: String): JsonObject?`; extension accessors `JsonObject.str/bool/long/double/obj/arr(key)`.
  - `sealed interface CliEvent` with `SystemInit(sessionId, permissionMode)`, `TextDelta(messageId, index, text)`, `BlockStop(messageId, index)`, `AssistantText(messageId, text)`, `ToolUse(toolUseId, name, input: JsonObject)`, `ToolResult(toolUseId, isError, content, filePath, originalFile, hasOriginalFile)`, `UserText(text)`, `PermissionRequest(requestId, toolName, toolUseId, input)`, `ControlResponse(requestId, isSuccess, response: JsonObject?, error)`, `Result(isError, subtype, text, durationMs, costUsd, errors)`, `Ignored(type)`, `Malformed(line)`.
  - `object CliParser { fun parse(line: String): List<CliEvent> }`
  - `object CliMessages { initialize(requestId), userText(text), allow(requestId, input), deny(requestId, message), interrupt(requestId), setPermissionMode(requestId, mode) }` — all return a single-line JSON `String`.
  - Test util `object Fx` (line builders, see below).

- [ ] **Step 1: Write test fixtures builder**

`src/test/kotlin/claudechat/testutil/Fx.kt`:
```kotlin
package claudechat.testutil

import kotlinx.serialization.json.JsonPrimitive

/** Builders for CLI stdout lines, shaped like real claude 2.1.317 output (trimmed). */
object Fx {
    fun q(s: String): String = JsonPrimitive(s).toString()

    fun init(sessionId: String = "sess-1", mode: String = "default") =
        """{"type":"system","subtype":"init","cwd":"/proj","session_id":"$sessionId","tools":["Read","Write"],"permissionMode":"$mode","claude_code_version":"2.1.317","uuid":"u1"}"""

    fun initResponse(requestId: String, mode: String = "default") =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"commands":[],"current_permission_mode":"$mode","session_state":"idle","claude_code_version":"2.1.317"}}}"""

    fun modeResponse(requestId: String, mode: String) =
        """{"type":"control_response","response":{"subtype":"success","request_id":"$requestId","response":{"mode":"$mode"}}}"""

    fun errorResponse(requestId: String, error: String) =
        """{"type":"control_response","response":{"subtype":"error","request_id":"$requestId","error":${q(error)}}}"""

    fun hookStarted() =
        """{"type":"system","subtype":"hook_started","hook_id":"h1","hook_name":"SessionStart:startup","hook_event":"SessionStart","uuid":"u0","session_id":"sess-1"}"""

    fun rateLimit() =
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1794110400,"rateLimitType":"seven_day"},"uuid":"u9","session_id":"sess-1"}"""

    fun messageStart(msg: String) =
        """{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[]}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u2","api_message_id":"$msg"}"""

    fun textDelta(msg: String, index: Int, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${q(text)}}},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u3","api_message_id":"$msg"}"""

    fun blockStop(msg: String, index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_stop","index":$index},"session_id":"sess-1","parent_tool_use_id":null,"uuid":"u4","api_message_id":"$msg"}"""

    fun assistantText(msg: String, text: String) =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"text","text":${q(text)}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u5"}"""

    fun assistantThinking(msg: String) =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"sig"}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u5"}"""

    fun toolUse(msg: String, toolUseId: String, name: String, inputJson: String) =
        """{"type":"assistant","message":{"model":"claude-opus-5-5","id":"$msg","type":"message","role":"assistant","content":[{"type":"tool_use","id":"$toolUseId","name":"$name","input":$inputJson,"caller":{"type":"direct"}}],"stop_reason":null},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u6"}"""

    fun canUseTool(requestId: String, toolUseId: String, name: String, inputJson: String) =
        """{"type":"control_request","request_id":"$requestId","request":{"subtype":"can_use_tool","tool_name":"$name","display_name":"$name","input":$inputJson,"description":"x","permission_suggestions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],"tool_use_id":"$toolUseId"}}"""

    fun toolResult(toolUseId: String, content: String, isError: Boolean = false, toolUseResultJson: String? = null): String {
        val err = if (isError) ""","is_error":true""" else ""
        val tur = if (toolUseResultJson != null) ""","tool_use_result":$toolUseResultJson""" else ""
        return """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$toolUseId","type":"tool_result","content":${q(content)}$err}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u7","timestamp":"2026-11-03T09:54:19.577Z"$tur}"""
    }

    fun editResult(path: String, original: String) =
        """{"filePath":${q(path)},"oldString":"two","newString":"2","originalFile":${q(original)},"structuredPatch":[],"userModified":false,"replaceAll":false}"""

    fun createResult(path: String) =
        """{"type":"create","filePath":${q(path)},"content":"x","structuredPatch":[],"originalFile":null,"userModified":false}"""

    fun interruptedUser() =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null,"session_id":"sess-1","uuid":"u8"}"""

    fun result(text: String = "DONE") =
        """{"type":"result","subtype":"success","is_error":false,"duration_ms":5566,"duration_api_ms":4514,"num_turns":2,"result":${q(text)},"stop_reason":"end_turn","session_id":"sess-1","total_cost_usd":0.1944822,"uuid":"u10"}"""

    fun errorResult() =
        """{"type":"result","subtype":"error_during_execution","is_error":true,"duration_ms":4010,"num_turns":2,"stop_reason":null,"session_id":"sess-1","total_cost_usd":0.19,"errors":["[ede_diagnostic] result_type=user"],"uuid":"u11"}"""
}
```

- [ ] **Step 2: Write the failing parser tests**

`src/test/kotlin/claudechat/cli/CliParserTest.kt`:
```kotlin
package claudechat.cli

import claudechat.testutil.Fx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliParserTest {
    private fun one(line: String): CliEvent = CliParser.parse(line).single()

    @Test fun systemInit() {
        assertEquals(CliEvent.SystemInit("sess-1", "acceptEdits"), one(Fx.init(mode = "acceptEdits")))
    }

    @Test fun otherSystemMessagesAreIgnored() {
        assertEquals(CliEvent.Ignored("system/hook_started"), one(Fx.hookStarted()))
        assertEquals(CliEvent.Ignored("rate_limit_event"), one(Fx.rateLimit()))
        assertEquals(CliEvent.Ignored("stream_event/message_start"), one(Fx.messageStart("m1")))
    }

    @Test fun textDeltaAndBlockStop() {
        assertEquals(CliEvent.TextDelta("m1", 1, "DO"), one(Fx.textDelta("m1", 1, "DO")))
        assertEquals(CliEvent.BlockStop("m1", 1), one(Fx.blockStop("m1", 1)))
    }

    @Test fun assistantTextAndToolUse() {
        assertEquals(CliEvent.AssistantText("m1", "DONE"), one(Fx.assistantText("m1", "DONE")))
        val tool = one(Fx.toolUse("m1", "t1", "Write", """{"file_path":"/p/a.txt","content":"hi"}""")) as CliEvent.ToolUse
        assertEquals("t1", tool.toolUseId)
        assertEquals("Write", tool.name)
        assertEquals("/p/a.txt", tool.input.str("file_path"))
    }

    @Test fun thinkingBlocksProduceNoEvents() {
        assertTrue(CliParser.parse(Fx.assistantThinking("m1")).isEmpty())
    }

    @Test fun permissionRequest() {
        val ev = one(Fx.canUseTool("r1", "t1", "Write", """{"file_path":"/p/a.txt","content":"hi"}""")) as CliEvent.PermissionRequest
        assertEquals("r1", ev.requestId)
        assertEquals("Write", ev.toolName)
        assertEquals("t1", ev.toolUseId)
        assertEquals("hi", ev.input.str("content"))
    }

    @Test fun toolResultWithOriginalFile() {
        val ev = one(Fx.toolResult("t1", "ok", toolUseResultJson = Fx.editResult("/p/f.txt", "line two\n"))) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", false, "ok", "/p/f.txt", "line two\n", true), ev)
    }

    @Test fun toolResultForCreatedFileHasNullOriginal() {
        val ev = one(Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult("/p/g.txt"))) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", false, "ok", "/p/g.txt", null, true), ev)
    }

    @Test fun toolResultErrorWithoutToolUseResult() {
        val ev = one(Fx.toolResult("t1", "denied", isError = true)) as CliEvent.ToolResult
        assertEquals(CliEvent.ToolResult("t1", true, "denied", null, null, false), ev)
    }

    @Test fun toolResultContentAsTextBlocks() {
        val line = """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"t1","type":"tool_result","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}}"""
        assertEquals("a\nb", (one(line) as CliEvent.ToolResult).content)
    }

    @Test fun interruptedUserText() {
        assertEquals(CliEvent.UserText("[Request interrupted by user]"), one(Fx.interruptedUser()))
    }

    @Test fun controlResponses() {
        val ok = one(Fx.initResponse("init-1", "plan")) as CliEvent.ControlResponse
        assertEquals("init-1", ok.requestId)
        assertTrue(ok.isSuccess)
        assertEquals("plan", ok.response!!.str("current_permission_mode"))
        assertEquals(CliEvent.ControlResponse("x", false, null, "nope"), one(Fx.errorResponse("x", "nope")))
    }

    @Test fun results() {
        assertEquals(CliEvent.Result(false, "success", "DONE", 5566, 0.1944822, emptyList()), one(Fx.result()))
        val err = one(Fx.errorResult()) as CliEvent.Result
        assertTrue(err.isError)
        assertEquals("error_during_execution", err.subtype)
        assertEquals(listOf("[ede_diagnostic] result_type=user"), err.errors)
    }

    @Test fun malformedAndUnknown() {
        assertEquals(CliEvent.Malformed("not json"), one("not json"))
        assertEquals(CliEvent.Ignored("mystery"), one("""{"type":"mystery"}"""))
    }
}
```

`src/test/kotlin/claudechat/cli/CliMessagesTest.kt`:
```kotlin
package claudechat.cli

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CliMessagesTest {
    @Test fun initialize() = assertEquals(
        """{"type":"control_request","request_id":"i1","request":{"subtype":"initialize","hooks":null}}""",
        CliMessages.initialize("i1"))

    @Test fun userText() = assertEquals(
        """{"type":"user","message":{"role":"user","content":"hi \"there\""}}""",
        CliMessages.userText("hi \"there\""))

    @Test fun allowEchoesInput() {
        val input = buildJsonObject { put("file_path", JsonPrimitive("/a")) }
        assertEquals(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"behavior":"allow","updatedInput":{"file_path":"/a"}}}}""",
            CliMessages.allow("r1", input))
    }

    @Test fun deny() = assertEquals(
        """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"behavior":"deny","message":"no"}}}""",
        CliMessages.deny("r1", "no"))

    @Test fun interrupt() = assertEquals(
        """{"type":"control_request","request_id":"x","request":{"subtype":"interrupt"}}""",
        CliMessages.interrupt("x"))

    @Test fun setPermissionMode() = assertEquals(
        """{"type":"control_request","request_id":"m","request":{"subtype":"set_permission_mode","mode":"plan"}}""",
        CliMessages.setPermissionMode("m", "plan"))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.cli.*'`
Expected: compilation FAILS (`Unresolved reference: CliParser`, `CliMessages`, `str`).

- [ ] **Step 4: Implement**

`src/main/kotlin/claudechat/cli/JsonExt.kt`:
```kotlin
package claudechat.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun parseJsonObject(line: String): JsonObject? =
    try { Json.parseToJsonElement(line) as? JsonObject } catch (e: Exception) { null }

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
```

`src/main/kotlin/claudechat/cli/CliEvent.kt`:
```kotlin
package claudechat.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface CliEvent {
    data class SystemInit(val sessionId: String, val permissionMode: String?) : CliEvent
    data class TextDelta(val messageId: String, val index: Int, val text: String) : CliEvent
    data class BlockStop(val messageId: String, val index: Int) : CliEvent
    data class AssistantText(val messageId: String, val text: String) : CliEvent
    data class ToolUse(val toolUseId: String, val name: String, val input: JsonObject) : CliEvent
    data class ToolResult(
        val toolUseId: String,
        val isError: Boolean,
        val content: String,
        val filePath: String?,
        val originalFile: String?,
        val hasOriginalFile: Boolean,
    ) : CliEvent
    data class UserText(val text: String) : CliEvent
    data class PermissionRequest(val requestId: String, val toolName: String, val toolUseId: String?, val input: JsonObject) : CliEvent
    data class ControlResponse(val requestId: String, val isSuccess: Boolean, val response: JsonObject?, val error: String?) : CliEvent
    data class Result(
        val isError: Boolean,
        val subtype: String,
        val text: String?,
        val durationMs: Long?,
        val costUsd: Double?,
        val errors: List<String>,
    ) : CliEvent
    data class Ignored(val type: String) : CliEvent
    data class Malformed(val line: String) : CliEvent
}

/** Turns one stdout line of `claude --output-format stream-json` into zero or more events. */
object CliParser {
    private val EMPTY = JsonObject(emptyMap())

    fun parse(line: String): List<CliEvent> {
        val obj = parseJsonObject(line) ?: return listOf(CliEvent.Malformed(line))
        return when (val type = obj.str("type")) {
            "system" ->
                if (obj.str("subtype") == "init") listOf(CliEvent.SystemInit(obj.str("session_id") ?: "", obj.str("permissionMode")))
                else listOf(CliEvent.Ignored("system/${obj.str("subtype")}"))
            "stream_event" -> listOf(streamEvent(obj))
            "assistant" -> assistant(obj)
            "user" -> user(obj)
            "control_request" -> listOf(controlRequest(obj))
            "control_response" -> listOf(controlResponse(obj))
            "result" -> listOf(result(obj))
            else -> listOf(CliEvent.Ignored(type ?: "?"))
        }
    }

    private fun streamEvent(obj: JsonObject): CliEvent {
        val messageId = obj.str("api_message_id") ?: ""
        val event = obj.obj("event") ?: return CliEvent.Ignored("stream_event/?")
        val index = event.long("index")?.toInt() ?: 0
        return when (val type = event.str("type")) {
            "content_block_delta" -> {
                val delta = event.obj("delta")
                if (delta?.str("type") == "text_delta") CliEvent.TextDelta(messageId, index, delta.str("text") ?: "")
                else CliEvent.Ignored("stream_event/content_block_delta/${delta?.str("type")}")
            }
            "content_block_stop" -> CliEvent.BlockStop(messageId, index)
            else -> CliEvent.Ignored("stream_event/$type")
        }
    }

    private fun assistant(obj: JsonObject): List<CliEvent> {
        val message = obj.obj("message") ?: return emptyList()
        val messageId = message.str("id") ?: ""
        return message.arr("content").orEmpty().mapNotNull { block ->
            val b = block as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> CliEvent.AssistantText(messageId, b.str("text") ?: "")
                "tool_use" -> CliEvent.ToolUse(b.str("id") ?: "", b.str("name") ?: "", b.obj("input") ?: EMPTY)
                else -> null
            }
        }
    }

    private fun user(obj: JsonObject): List<CliEvent> {
        val content = obj.obj("message")?.get("content") ?: return emptyList()
        if (content is JsonPrimitive) return listOf(CliEvent.UserText(content.content))
        val blocks = (content as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val toolUseResult = obj["tool_use_result"] as? JsonObject
        val singleResult = blocks.count { it.str("type") == "tool_result" } == 1
        return blocks.mapNotNull { b ->
            when (b.str("type")) {
                "tool_result" -> {
                    val extra = if (singleResult) toolUseResult else null
                    val original = extra?.get("originalFile")
                    CliEvent.ToolResult(
                        toolUseId = b.str("tool_use_id") ?: "",
                        isError = b.bool("is_error") == true,
                        content = textOf(b["content"]),
                        filePath = extra?.str("filePath"),
                        originalFile = if (original == null || original is JsonNull) null else (original as? JsonPrimitive)?.content,
                        hasOriginalFile = extra?.containsKey("originalFile") == true,
                    )
                }
                "text" -> CliEvent.UserText(b.str("text") ?: "")
                else -> null
            }
        }
    }

    private fun textOf(content: Any?): String = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>().mapNotNull { it.str("text") }.joinToString("\n")
        else -> ""
    }

    private fun controlRequest(obj: JsonObject): CliEvent {
        val request = obj.obj("request") ?: return CliEvent.Ignored("control_request/?")
        return when (val subtype = request.str("subtype")) {
            "can_use_tool" -> CliEvent.PermissionRequest(
                requestId = obj.str("request_id") ?: "",
                toolName = request.str("tool_name") ?: "",
                toolUseId = request.str("tool_use_id"),
                input = request.obj("input") ?: EMPTY,
            )
            else -> CliEvent.Ignored("control_request/$subtype")
        }
    }

    private fun controlResponse(obj: JsonObject): CliEvent {
        val response = obj.obj("response") ?: return CliEvent.Ignored("control_response/?")
        return CliEvent.ControlResponse(
            requestId = response.str("request_id") ?: "",
            isSuccess = response.str("subtype") == "success",
            response = response.obj("response"),
            error = response.str("error"),
        )
    }

    private fun result(obj: JsonObject) = CliEvent.Result(
        isError = obj.bool("is_error") == true,
        subtype = obj.str("subtype") ?: "",
        text = obj.str("result"),
        durationMs = obj.long("duration_ms"),
        costUsd = obj.double("total_cost_usd"),
        errors = obj.arr("errors").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
    )
}
```

`src/main/kotlin/claudechat/cli/CliMessages.kt`:
```kotlin
package claudechat.cli

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Builders for lines written to the CLI's stdin. */
object CliMessages {
    fun initialize(requestId: String): String = controlRequest(requestId) {
        put("subtype", "initialize")
        put("hooks", JsonNull)
    }

    fun userText(text: String): String = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            put("content", text)
        }
    }.toString()

    fun allow(requestId: String, input: JsonObject): String = controlResponse(requestId) {
        put("behavior", "allow")
        put("updatedInput", input)
    }

    fun deny(requestId: String, message: String): String = controlResponse(requestId) {
        put("behavior", "deny")
        put("message", message)
    }

    fun interrupt(requestId: String): String = controlRequest(requestId) { put("subtype", "interrupt") }

    fun setPermissionMode(requestId: String, mode: String): String = controlRequest(requestId) {
        put("subtype", "set_permission_mode")
        put("mode", mode)
    }

    private fun controlRequest(requestId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request", body)
    }.toString()

    private fun controlResponse(requestId: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("subtype", "success")
            put("request_id", requestId)
            putJsonObject("response", body)
        }
    }.toString()
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.cli.*'`
Expected: all tests in `CliParserTest` and `CliMessagesTest` PASS.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Parse Claude CLI stream-json events and build control messages"
```

---

### Task 3: Command line and binary resolution

**Files:**
- Create: `src/main/kotlin/claudechat/cli/ClaudeCommand.kt`
- Test: `src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt`

**Interfaces:**
- Produces:
  - `enum class PermissionMode(val cli: String) { DEFAULT("default"), ACCEPT_EDITS("acceptEdits"), PLAN("plan"), AUTO("auto"); companion object { fun fromCli(value: String?): PermissionMode? } }`
  - `data class SessionLaunch(val resumeId: String?, val permissionMode: PermissionMode?)`
  - `object ClaudeCommand { fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> }`
  - `object ClaudeBinary { fun resolve(configured: String, pathEnv: String?, home: String, isExecutable: (String) -> Boolean): String? }`

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/claudechat/cli/ClaudeCommandTest.kt`:
```kotlin
package claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCommandTest {
    private val base = listOf(
        "/bin/claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json",
        "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
    )

    @Test fun newSessionWithoutMode() =
        assertEquals(base, ClaudeCommand.build("/bin/claude", SessionLaunch(null, null), emptyList()))

    @Test fun resumeWithModeAndExtraArgs() = assertEquals(
        base + listOf("--permission-mode", "acceptEdits", "--resume", "abc", "--model", "sonnet"),
        ClaudeCommand.build("/bin/claude", SessionLaunch("abc", PermissionMode.ACCEPT_EDITS), listOf("--model", "sonnet")))

    @Test fun permissionModeFromCli() {
        assertEquals(PermissionMode.PLAN, PermissionMode.fromCli("plan"))
        assertNull(PermissionMode.fromCli("bypassPermissions"))
        assertNull(PermissionMode.fromCli(null))
    }

    @Test fun configuredPathWins() =
        assertEquals("/x/claude", ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { true })

    @Test fun configuredPathThatIsNotExecutableGivesNull() =
        assertNull(ClaudeBinary.resolve("/x/claude", "/usr/bin", "/home/u") { it != "/x/claude" })

    @Test fun searchesPathThenFallbacks() {
        assertEquals("/b/claude", ClaudeBinary.resolve("", "/a:/b", "/home/u") { it == "/b/claude" })
        assertEquals("/home/u/.local/bin/claude", ClaudeBinary.resolve("", "/a", "/home/u") { it == "/home/u/.local/bin/claude" })
        assertEquals("/opt/homebrew/bin/claude", ClaudeBinary.resolve("  ", null, "/home/u") { it == "/opt/homebrew/bin/claude" })
        assertNull(ClaudeBinary.resolve("", "/a", "/home/u") { false })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.cli.ClaudeCommandTest'`
Expected: compilation FAILS (`Unresolved reference: ClaudeCommand`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/cli/ClaudeCommand.kt`:
```kotlin
package claudechat.cli

enum class PermissionMode(val cli: String) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    AUTO("auto");

    companion object {
        fun fromCli(value: String?): PermissionMode? = entries.firstOrNull { it.cli == value }
    }
}

/** What a new CLI process should start as. */
data class SessionLaunch(val resumeId: String?, val permissionMode: PermissionMode?)

object ClaudeCommand {
    fun build(binary: String, launch: SessionLaunch, extraArgs: List<String>): List<String> = buildList {
        add(binary)
        addAll(listOf(
            "-p", "--input-format", "stream-json", "--output-format", "stream-json",
            "--include-partial-messages", "--verbose", "--permission-prompt-tool", "stdio",
        ))
        launch.permissionMode?.let { add("--permission-mode"); add(it.cli) }
        launch.resumeId?.let { add("--resume"); add(it) }
        addAll(extraArgs)
    }
}

object ClaudeBinary {
    /** Configured path if set (null if not executable); otherwise PATH entries, then common install locations. */
    fun resolve(configured: String, pathEnv: String?, home: String, isExecutable: (String) -> Boolean): String? {
        if (configured.isNotBlank()) return configured.trim().takeIf(isExecutable)
        val fromPath = pathEnv.orEmpty().split(':').filter { it.isNotBlank() }.map { "${it.trimEnd('/')}/claude" }
        val fallbacks = listOf("$home/.local/bin/claude", "/opt/homebrew/bin/claude", "/usr/local/bin/claude")
        return (fromPath + fallbacks).firstOrNull(isExecutable)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.cli.ClaudeCommandTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Build claude command line and resolve the binary"
```

---

### Task 4: Chat item model and JSON

**Files:**
- Create: `src/main/kotlin/claudechat/session/ChatItem.kt`
- Test: `src/test/kotlin/claudechat/session/ChatItemTest.kt`

**Interfaces:**
- Produces:
  - `enum class ToolStatus { RUNNING, AWAITING_PERMISSION, DONE, ERROR, DENIED }`
  - `sealed interface ChatItem { val id: String }` with `UserText(id, text)`, `AssistantText(id, text, streaming)`, `ToolCall(id, name, input: JsonObject, filePath: String?, status: ToolStatus, permissionRequestId: String?, resultPreview: String?, hasDiff: Boolean)`, `TurnResult(id, isError, text: String?, durationMs: Long?, costUsd: Double?)`, `Notice(id, text, isError)`.
  - `fun ChatItem.toJson(): JsonObject` — field `kind` is `user|assistant|tool|result|notice`; tool `status` is the lowercase enum name.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/session/ChatItemTest.kt`:
```kotlin
package claudechat.session

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatItemTest {
    @Test fun user() = assertEquals(
        """{"id":"u1","kind":"user","text":"hi"}""",
        ChatItem.UserText("u1", "hi").toJson().toString())

    @Test fun assistant() = assertEquals(
        """{"id":"a1","kind":"assistant","text":"x","streaming":true}""",
        ChatItem.AssistantText("a1", "x", true).toJson().toString())

    @Test fun tool() {
        val input = buildJsonObject { put("file_path", JsonPrimitive("/p/a.ts")) }
        assertEquals(
            """{"id":"t1","kind":"tool","name":"Edit","input":{"file_path":"/p/a.ts"},"filePath":"/p/a.ts","status":"awaiting_permission","permissionRequestId":"r1","resultPreview":null,"hasDiff":false}""",
            ChatItem.ToolCall("t1", "Edit", input, "/p/a.ts", ToolStatus.AWAITING_PERMISSION, "r1", null, false).toJson().toString())
    }

    @Test fun result() = assertEquals(
        """{"id":"r1","kind":"result","isError":false,"text":null,"durationMs":5566,"costUsd":0.19}""",
        ChatItem.TurnResult("r1", false, null, 5566, 0.19).toJson().toString())

    @Test fun notice() = assertEquals(
        """{"id":"n1","kind":"notice","text":"Interrupted","isError":false}""",
        ChatItem.Notice("n1", "Interrupted", false).toJson().toString())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.session.ChatItemTest'`
Expected: compilation FAILS (`Unresolved reference: ChatItem`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/session/ChatItem.kt`:
```kotlin
package claudechat.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolStatus { RUNNING, AWAITING_PERMISSION, DONE, ERROR, DENIED }

/** One entry in the chat log. Items are upserted by [id]. */
sealed interface ChatItem {
    val id: String

    data class UserText(override val id: String, val text: String) : ChatItem
    data class AssistantText(override val id: String, val text: String, val streaming: Boolean) : ChatItem
    data class ToolCall(
        override val id: String,
        val name: String,
        val input: JsonObject,
        val filePath: String?,
        val status: ToolStatus,
        val permissionRequestId: String?,
        val resultPreview: String?,
        val hasDiff: Boolean,
    ) : ChatItem
    data class TurnResult(override val id: String, val isError: Boolean, val text: String?, val durationMs: Long?, val costUsd: Double?) : ChatItem
    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatItem
}

fun ChatItem.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    when (val item = this@toJson) {
        is ChatItem.UserText -> { put("kind", "user"); put("text", item.text) }
        is ChatItem.AssistantText -> { put("kind", "assistant"); put("text", item.text); put("streaming", item.streaming) }
        is ChatItem.ToolCall -> {
            put("kind", "tool")
            put("name", item.name)
            put("input", item.input)
            put("filePath", item.filePath)
            put("status", item.status.name.lowercase())
            put("permissionRequestId", item.permissionRequestId)
            put("resultPreview", item.resultPreview)
            put("hasDiff", item.hasDiff)
        }
        is ChatItem.TurnResult -> {
            put("kind", "result")
            put("isError", item.isError)
            put("text", item.text)
            put("durationMs", item.durationMs)
            put("costUsd", item.costUsd)
        }
        is ChatItem.Notice -> { put("kind", "notice"); put("text", item.text); put("isError", item.isError) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.session.ChatItemTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add chat item model with JSON serialisation"
```

---

### Task 5: ChatSession state machine

**Files:**
- Create: `src/main/kotlin/claudechat/cli/CliConnection.kt`, `src/main/kotlin/claudechat/session/ChatSession.kt`
- Create: `src/test/kotlin/claudechat/testutil/FakeConnection.kt`
- Test: `src/test/kotlin/claudechat/session/ChatSessionTest.kt`

**Interfaces:**
- Consumes: `CliParser`, `CliEvent`, `CliMessages`, `PermissionMode`, `SessionLaunch` (Tasks 2–3); `ChatItem`, `ToolStatus` (Task 4); `Fx` (Task 2).
- Produces:
  - `interface CliConnection { fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit); fun send(line: String); fun stop() }`
  - `enum class SessionState { IDLE, RUNNING, AWAITING_PERMISSION, DEAD }`
  - `interface ChatListener { fun onItem(item: ChatItem); fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) }` — called while the session lock is held; implementations must not call back into the session synchronously.
  - `sealed interface PermissionDecision { data object Allow; data object AllowForSession; data class Deny(val message: String) }`
  - `data class FileDiff(val filePath: String, val originalContent: String)`
  - `class ChatSession(connect: (SessionLaunch) -> CliConnection, listener: ChatListener, resumeId: String? = null, initialMode: PermissionMode? = null, log: (String) -> Unit = {})` with `start()`, `send(text: String, prefix: String? = null)`, `respond(requestId: String, decision: PermissionDecision)`, `setPermissionMode(mode: PermissionMode)`, `interrupt()`, `restart()`, `dispose()`, `diffFor(toolUseId: String): FileDiff?`, read-only `sessionId`, `mode`, `state`.
  - Test util `FakeConnection` and `RecordingListener`.

- [ ] **Step 1: Write the test utilities**

`src/test/kotlin/claudechat/testutil/FakeConnection.kt`:
```kotlin
package claudechat.testutil

import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.ChatItem
import claudechat.session.ChatListener
import claudechat.session.SessionState
import kotlinx.serialization.json.JsonObject

class FakeConnection : CliConnection {
    val sent = mutableListOf<String>()
    var stopped = false
    private var onLine: ((String) -> Unit)? = null
    private var onExit: ((Int, String) -> Unit)? = null

    override fun start(onLine: (String) -> Unit, onExit: (Int, String) -> Unit) {
        this.onLine = onLine
        this.onExit = onExit
    }
    override fun send(line: String) { sent += line }
    override fun stop() { stopped = true }

    fun emit(vararg lines: String) = lines.forEach { onLine!!(it) }
    fun exit(code: Int, stderr: String) = onExit!!(code, stderr)

    fun sentJson(): List<JsonObject> = sent.map { parseJsonObject(it)!! }
    /** request_id of the most recent control_request with the given subtype. */
    fun lastRequestId(subtype: String): String =
        sentJson().last { it.str("type") == "control_request" && it.obj("request")?.str("subtype") == subtype }.str("request_id")!!
    fun userMessages(): List<String> =
        sentJson().filter { it.str("type") == "user" }.map { it.obj("message")!!.str("content")!! }
}

class RecordingListener : ChatListener {
    val items = mutableListOf<ChatItem>()
    val states = mutableListOf<Triple<SessionState, String?, PermissionMode?>>()
    override fun onItem(item: ChatItem) { items += item }
    override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) { states += Triple(state, sessionId, mode) }

    /** Latest version of every item, in first-seen order. */
    fun current(): List<ChatItem> = items.associateBy { it.id }.values.toList()
    inline fun <reified T : ChatItem> latest(): T = items.filterIsInstance<T>().last()
    fun lastState(): SessionState = states.last().first
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/kotlin/claudechat/session/ChatSessionTest.kt`:
```kotlin
package claudechat.session

import claudechat.cli.CliMessages
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.cli.obj
import claudechat.cli.str
import claudechat.testutil.FakeConnection
import claudechat.testutil.Fx
import claudechat.testutil.RecordingListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private val listener = RecordingListener()
    private val conn get() = connections.last()

    private fun session(resumeId: String? = null, mode: PermissionMode? = null, failConnect: Boolean = false) =
        ChatSession(
            connect = { launch ->
                if (failConnect) throw IllegalStateException("claude not found")
                launches += launch
                FakeConnection().also { connections += it }
            },
            listener = listener,
            resumeId = resumeId,
            initialMode = mode,
        ).also { it.start() }

    private val writeInput = """{"file_path":"/p/a.txt","content":"hi"}"""

    // --- lifecycle and turns ---

    @Test fun startSendsInitializeAndIsIdle() {
        session()
        assertEquals("initialize", conn.sentJson().single().obj("request")!!.str("subtype"))
        assertEquals(SessionState.IDLE, listener.lastState())
    }

    @Test fun initResponseAndSystemInitSetModeAndSessionId() {
        val s = session()
        conn.emit(Fx.initResponse(conn.lastRequestId("initialize"), "plan"))
        assertEquals(PermissionMode.PLAN, s.mode)
        conn.emit(Fx.init("sess-9", "acceptEdits"))
        assertEquals("sess-9", s.sessionId)
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun sendShowsUserTextAndSendsPrefixedPayload() {
        val s = session()
        s.send("hello", prefix = "[IDE] note")
        assertEquals("hello", listener.latest<ChatItem.UserText>().text)
        assertEquals(listOf("[IDE] note\n\nhello"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun streamedTextIsOneItemAndFinalAssistantMessageIsNotDuplicated() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.messageStart("m1"), Fx.textDelta("m1", 1, "DO"), Fx.textDelta("m1", 1, "NE"))
        val streaming = listener.latest<ChatItem.AssistantText>()
        assertEquals("DONE", streaming.text)
        assertTrue(streaming.streaming)
        conn.emit(Fx.blockStop("m1", 1), Fx.assistantText("m1", "DONE"))
        val texts = listener.current().filterIsInstance<ChatItem.AssistantText>()
        assertEquals(1, texts.size)
        assertFalse(texts.single().streaming)
    }

    @Test fun assistantTextWithoutStreamingCreatesItem() {
        val s = session()
        s.send("hi")
        conn.emit(Fx.assistantText("m2", "plain"))
        assertEquals(ChatItem.AssistantText(listener.latest<ChatItem.AssistantText>().id, "plain", false), listener.latest<ChatItem.AssistantText>())
    }

    @Test fun resultEndsTurnAndFlushesQueue() {
        val s = session()
        s.send("one")
        s.send("two")
        assertEquals(listOf("one"), conn.userMessages())
        conn.emit(Fx.result())
        assertEquals(listOf("one", "two"), conn.userMessages())
        assertEquals(SessionState.RUNNING, s.state)
        conn.emit(Fx.result())
        assertEquals(SessionState.IDLE, s.state)
        val result = listener.latest<ChatItem.TurnResult>()
        assertFalse(result.isError)
        assertEquals(5566L, result.durationMs)
    }

    @Test fun errorResultCarriesText() {
        val s = session()
        s.send("x")
        conn.emit(Fx.errorResult())
        val r = listener.latest<ChatItem.TurnResult>()
        assertTrue(r.isError)
        assertEquals("[ede_diagnostic] result_type=user", r.text)
    }

    // --- tools and permissions ---

    @Test fun permissionRequestMarksToolAndState() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.AWAITING_PERMISSION, tool.status)
        assertEquals("r1", tool.permissionRequestId)
        assertEquals("/p/a.txt", tool.filePath)
        assertEquals(SessionState.AWAITING_PERMISSION, s.state)
    }

    @Test fun allowSendsAllowWithOriginalInput() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Allow)
        val response = conn.sentJson().last().obj("response")!!
        assertEquals("r1", response.str("request_id"))
        assertEquals("allow", response.obj("response")!!.str("behavior"))
        assertEquals("hi", response.obj("response")!!.obj("updatedInput")!!.str("content"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun denySendsDenyAndToolEndsDenied() {
        val s = session()
        s.send("make file")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.Deny("use b.txt"))
        assertEquals(CliMessages.deny("r1", "use b.txt"), conn.sent.last())
        conn.emit(Fx.toolResult("t1", "use b.txt", isError = true))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun allowForSessionAutoAllowsLaterRequestsForSameTool() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.respond("r1", PermissionDecision.AllowForSession)
        conn.emit(Fx.toolUse("m2", "t2", "Write", writeInput), Fx.canUseTool("r2", "t2", "Write", writeInput))
        assertEquals("r2", conn.sentJson().last().obj("response")!!.str("request_id"))
        assertEquals(ToolStatus.RUNNING, listener.latest<ChatItem.ToolCall>().status)
        assertEquals(SessionState.RUNNING, s.state)
    }

    @Test fun editResultEnablesDiff() {
        val s = session()
        s.send("x")
        conn.emit(
            Fx.toolUse("m1", "t1", "Edit", """{"file_path":"/p/f.txt","old_string":"two","new_string":"2"}"""),
            Fx.toolResult("t1", "ok", toolUseResultJson = Fx.editResult("/p/f.txt", "line two\n")),
        )
        val tool = listener.latest<ChatItem.ToolCall>()
        assertEquals(ToolStatus.DONE, tool.status)
        assertTrue(tool.hasDiff)
        assertEquals("ok", tool.resultPreview)
        assertEquals(FileDiff("/p/f.txt", "line two\n"), s.diffFor("t1"))
    }

    @Test fun createdFileDiffsAgainstEmpty() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.toolResult("t1", "ok", toolUseResultJson = Fx.createResult("/p/a.txt")))
        assertEquals(FileDiff("/p/a.txt", ""), s.diffFor("t1"))
    }

    @Test fun readResultHasNoDiff() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Read", """{"file_path":"/p/f.txt"}"""), Fx.toolResult("t1", "contents"))
        assertFalse(listener.latest<ChatItem.ToolCall>().hasDiff)
        assertNull(s.diffFor("t1"))
    }

    // --- control ---

    @Test fun interruptSendsControlRequestAndShowsNotice() {
        val s = session()
        s.send("long task")
        s.send("queued")
        s.interrupt()
        assertEquals("interrupt", conn.sentJson().last().obj("request")!!.str("subtype"))
        conn.emit(Fx.interruptedUser(), Fx.errorResult())
        assertEquals("Interrupted", listener.latest<ChatItem.Notice>().text)
        assertTrue(listener.items.none { it is ChatItem.TurnResult })
        assertEquals(listOf("long task"), conn.userMessages())
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun interruptDeniesPendingPermission() {
        val s = session()
        s.send("x")
        conn.emit(Fx.toolUse("m1", "t1", "Write", writeInput), Fx.canUseTool("r1", "t1", "Write", writeInput))
        s.interrupt()
        val sent = conn.sentJson()
        assertEquals("deny", sent[sent.size - 2].obj("response")!!.obj("response")!!.str("behavior"))
        assertEquals(ToolStatus.DENIED, listener.latest<ChatItem.ToolCall>().status)
    }

    @Test fun setPermissionModeSendsRequest() {
        val s = session()
        s.setPermissionMode(PermissionMode.ACCEPT_EDITS)
        val req = conn.sentJson().last().obj("request")!!
        assertEquals("set_permission_mode", req.str("subtype"))
        assertEquals("acceptEdits", req.str("mode"))
        assertEquals(PermissionMode.ACCEPT_EDITS, s.mode)
    }

    @Test fun failedControlRequestShowsNotice() {
        session()
        conn.emit(Fx.errorResponse("x", "unsupported"))
        assertTrue(listener.latest<ChatItem.Notice>().text.contains("unsupported"))
    }

    @Test fun processExitMarksDeadAndRestartResumesWithMode() {
        val s = session(mode = PermissionMode.PLAN)
        s.send("x")
        conn.emit(Fx.init("sess-7", "plan"))
        conn.exit(1, "boom")
        assertEquals(SessionState.DEAD, s.state)
        val notice = listener.latest<ChatItem.Notice>()
        assertTrue(notice.isError)
        assertTrue(notice.text.contains("boom"))
        s.send("ignored while dead")
        assertEquals(listOf("x"), connections.first().userMessages())

        s.restart()
        assertEquals(SessionLaunch("sess-7", PermissionMode.PLAN), launches.last())
        assertEquals(2, connections.size)
        assertEquals(SessionState.IDLE, s.state)
    }

    @Test fun linesFromDisposedConnectionAreIgnored() {
        val s = session()
        val old = conn
        s.dispose()
        assertTrue(old.stopped)
        val before = listener.items.size
        old.emit(Fx.assistantText("m1", "late"))
        assertEquals(before, listener.items.size)
    }

    @Test fun connectFailureIsReportedAsDead() {
        session(failConnect = true)
        assertEquals(SessionState.DEAD, listener.lastState())
        assertEquals("claude not found", listener.latest<ChatItem.Notice>().text)
    }

    @Test fun resumeIdAndModeArePassedToFirstLaunch() {
        session(resumeId = "abc", mode = PermissionMode.AUTO)
        assertEquals(SessionLaunch("abc", PermissionMode.AUTO), launches.single())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gw test --tests 'claudechat.session.ChatSessionTest'`
Expected: compilation FAILS (`Unresolved reference: ChatSession`, `CliConnection`).

- [ ] **Step 4: Implement**

`src/main/kotlin/claudechat/cli/CliConnection.kt`:
```kotlin
package claudechat.cli

/** A bidirectional NDJSON channel to one claude process. */
interface CliConnection {
    /** Begin delivering stdout lines and the exit notification. Callbacks may run on any thread. */
    fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit)
    fun send(line: String)
    fun stop()
}
```

`src/main/kotlin/claudechat/session/ChatSession.kt`:
```kotlin
package claudechat.session

import claudechat.cli.CliConnection
import claudechat.cli.CliEvent
import claudechat.cli.CliMessages
import claudechat.cli.CliParser
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.cli.str
import kotlinx.serialization.json.JsonObject

enum class SessionState { IDLE, RUNNING, AWAITING_PERMISSION, DEAD }

/** Called while the session lock is held: do not call back into the session synchronously. */
interface ChatListener {
    fun onItem(item: ChatItem)
    fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?)
}

sealed interface PermissionDecision {
    data object Allow : PermissionDecision
    data object AllowForSession : PermissionDecision
    data class Deny(val message: String) : PermissionDecision
}

data class FileDiff(val filePath: String, val originalContent: String)

/** One conversation with one claude process (respawned with --resume on restart). Thread-safe. */
class ChatSession(
    private val connect: (SessionLaunch) -> CliConnection,
    private val listener: ChatListener,
    resumeId: String? = null,
    initialMode: PermissionMode? = null,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()

    var sessionId: String? = resumeId
        private set
    var mode: PermissionMode? = initialMode
        private set
    var state: SessionState = SessionState.IDLE
        private set

    private var connection: CliConnection? = null
    private var generation = 0
    private var seq = 0
    private var interruptRequested = false
    private val queue = ArrayDeque<String>()
    private val toolCalls = HashMap<String, ChatItem.ToolCall>()
    private val streamed = HashMap<String, ChatItem.AssistantText>()
    private val streamedMessages = HashSet<String>()
    private val pendingPermissions = LinkedHashMap<String, CliEvent.PermissionRequest>()
    private val deniedToolUses = HashSet<String>()
    private val sessionAllowedTools = HashSet<String>()
    private val diffs = HashMap<String, FileDiff>()

    fun start() {
        synchronized(lock) {
            val gen = ++generation
            try {
                val conn = connect(SessionLaunch(sessionId, mode))
                connection = conn
                conn.start(
                    onLine = { line -> synchronized(lock) { if (gen == generation) handleLine(line) } },
                    onExit = { code, tail -> synchronized(lock) { if (gen == generation) handleExit(code, tail) } },
                )
                conn.send(CliMessages.initialize(nextId("init")))
            } catch (e: Exception) {
                connection = null
                emit(ChatItem.Notice(nextId("notice"), e.message ?: e.toString(), isError = true))
                setState(SessionState.DEAD)
                return
            }
            setState(SessionState.IDLE)
        }
    }

    /** Shows [text] in the chat; sends "[prefix]\n\n[text]" to Claude. Queued while a turn is running. */
    fun send(text: String, prefix: String? = null) {
        synchronized(lock) {
            if (state == SessionState.DEAD) return
            emit(ChatItem.UserText(nextId("user"), text))
            val payload = if (prefix.isNullOrBlank()) text else "$prefix\n\n$text"
            if (state == SessionState.IDLE) {
                connection?.send(CliMessages.userText(payload))
                setState(SessionState.RUNNING)
            } else {
                queue.addLast(payload)
            }
        }
    }

    fun respond(requestId: String, decision: PermissionDecision) {
        synchronized(lock) {
            val req = pendingPermissions.remove(requestId) ?: return
            val toolId = req.toolUseId ?: "perm-$requestId"
            when (decision) {
                PermissionDecision.Allow -> connection?.send(CliMessages.allow(requestId, req.input))
                PermissionDecision.AllowForSession -> {
                    sessionAllowedTools += req.toolName
                    connection?.send(CliMessages.allow(requestId, req.input))
                }
                is PermissionDecision.Deny -> {
                    deniedToolUses += toolId
                    connection?.send(CliMessages.deny(requestId, decision.message))
                }
            }
            val status = if (decision is PermissionDecision.Deny) ToolStatus.DENIED else ToolStatus.RUNNING
            toolCalls[toolId]?.let { upsertTool(it.copy(status = status, permissionRequestId = null)) }
            if (pendingPermissions.isEmpty() && state == SessionState.AWAITING_PERMISSION) setState(SessionState.RUNNING)
        }
    }

    fun setPermissionMode(newMode: PermissionMode) {
        synchronized(lock) {
            mode = newMode
            connection?.send(CliMessages.setPermissionMode(nextId("mode"), newMode.cli))
            notifyState()
        }
    }

    fun interrupt() {
        synchronized(lock) {
            if (state != SessionState.RUNNING && state != SessionState.AWAITING_PERMISSION) return
            interruptRequested = true
            queue.clear()
            for ((requestId, req) in pendingPermissions) {
                val toolId = req.toolUseId ?: "perm-$requestId"
                deniedToolUses += toolId
                connection?.send(CliMessages.deny(requestId, "Interrupted by the user."))
                toolCalls[toolId]?.let { upsertTool(it.copy(status = ToolStatus.DENIED, permissionRequestId = null)) }
            }
            pendingPermissions.clear()
            connection?.send(CliMessages.interrupt(nextId("interrupt")))
        }
    }

    /** Respawns a dead session with --resume and the current permission mode. */
    fun restart() {
        synchronized(lock) {
            if (state != SessionState.DEAD) return
            start()
        }
    }

    fun dispose() {
        synchronized(lock) {
            generation++
            connection?.stop()
            connection = null
        }
    }

    fun diffFor(toolUseId: String): FileDiff? = synchronized(lock) { diffs[toolUseId] }

    private fun handleLine(line: String) {
        for (event in CliParser.parse(line)) handle(event)
    }

    private fun handle(event: CliEvent) {
        when (event) {
            is CliEvent.SystemInit -> {
                if (event.sessionId.isNotBlank()) sessionId = event.sessionId
                PermissionMode.fromCli(event.permissionMode)?.let { mode = it }
                notifyState()
            }
            is CliEvent.ControlResponse -> {
                if (!event.isSuccess) {
                    emit(ChatItem.Notice(nextId("notice"), "Claude rejected a request: ${event.error ?: "unknown error"}", isError = true))
                } else {
                    val reported = event.response?.let { it.str("current_permission_mode") ?: it.str("mode") }
                    PermissionMode.fromCli(reported)?.let { mode = it; notifyState() }
                }
            }
            is CliEvent.TextDelta -> {
                val key = "${event.messageId}:${event.index}"
                streamedMessages += event.messageId
                val current = streamed[key] ?: ChatItem.AssistantText("text-$key", "", true)
                val next = current.copy(text = current.text + event.text, streaming = true)
                streamed[key] = next
                emit(next)
            }
            is CliEvent.BlockStop -> streamed.remove("${event.messageId}:${event.index}")?.let { emit(it.copy(streaming = false)) }
            is CliEvent.AssistantText ->
                if (event.messageId !in streamedMessages) emit(ChatItem.AssistantText(nextId("text"), event.text, false))
            is CliEvent.ToolUse -> {
                val base = toolCalls[event.toolUseId]
                    ?: ChatItem.ToolCall(event.toolUseId, event.name, event.input, null, ToolStatus.RUNNING, null, null, false)
                upsertTool(base.copy(name = event.name, input = event.input, filePath = filePathOf(event.input)))
            }
            is CliEvent.PermissionRequest -> onPermissionRequest(event)
            is CliEvent.ToolResult -> onToolResult(event)
            is CliEvent.UserText -> Unit
            is CliEvent.Result -> onResult(event)
            is CliEvent.Ignored -> Unit
            is CliEvent.Malformed -> log("Malformed line from claude: ${event.line.take(200)}")
        }
    }

    private fun onPermissionRequest(event: CliEvent.PermissionRequest) {
        if (event.toolName in sessionAllowedTools) {
            connection?.send(CliMessages.allow(event.requestId, event.input))
            return
        }
        pendingPermissions[event.requestId] = event
        val id = event.toolUseId ?: "perm-${event.requestId}"
        val base = toolCalls[id]
            ?: ChatItem.ToolCall(id, event.toolName, event.input, filePathOf(event.input), ToolStatus.RUNNING, null, null, false)
        upsertTool(base.copy(status = ToolStatus.AWAITING_PERMISSION, permissionRequestId = event.requestId))
        setState(SessionState.AWAITING_PERMISSION)
    }

    private fun onToolResult(event: CliEvent.ToolResult) {
        val tool = toolCalls[event.toolUseId] ?: return
        val status = when {
            event.toolUseId in deniedToolUses -> ToolStatus.DENIED
            event.isError -> ToolStatus.ERROR
            else -> ToolStatus.DONE
        }
        val diffPath = event.filePath ?: tool.filePath
        val hasDiff = event.hasOriginalFile && diffPath != null && !event.isError
        if (hasDiff) diffs[tool.id] = FileDiff(diffPath!!, event.originalFile ?: "")
        upsertTool(tool.copy(status = status, permissionRequestId = null, resultPreview = event.content.take(4000), hasDiff = hasDiff))
    }

    private fun onResult(event: CliEvent.Result) {
        pendingPermissions.clear()
        if (interruptRequested) {
            interruptRequested = false
            emit(ChatItem.Notice(nextId("notice"), "Interrupted", isError = false))
        } else {
            val text = if (event.isError) event.text ?: event.errors.joinToString("\n") else null
            emit(ChatItem.TurnResult(nextId("result"), event.isError, text, event.durationMs, event.costUsd))
        }
        val next = queue.removeFirstOrNull()
        if (next != null) {
            connection?.send(CliMessages.userText(next))
            setState(SessionState.RUNNING)
        } else {
            setState(SessionState.IDLE)
        }
    }

    private fun handleExit(code: Int, stderrTail: String) {
        connection = null
        pendingPermissions.clear()
        queue.clear()
        val detail = stderrTail.trim().takeLast(2000)
        val text = if (detail.isEmpty()) "Claude exited (code $code)." else "Claude exited (code $code).\n$detail"
        emit(ChatItem.Notice(nextId("notice"), text, isError = true))
        setState(SessionState.DEAD)
    }

    private fun filePathOf(input: JsonObject): String? = input.str("file_path") ?: input.str("notebook_path")
    private fun nextId(prefix: String) = "$prefix-${++seq}"
    private fun upsertTool(tool: ChatItem.ToolCall) { toolCalls[tool.id] = tool; emit(tool) }
    private fun emit(item: ChatItem) = listener.onItem(item)
    private fun setState(newState: SessionState) { state = newState; notifyState() }
    private fun notifyState() = listener.onState(state, sessionId, mode)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.ChatSessionTest'`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Add ChatSession: turns, streaming, permissions, interrupt, restart"
```

---

### Task 6: Real process transport

**Files:**
- Create: `src/main/kotlin/claudechat/cli/ClaudeProcess.kt`
- Test: `src/test/kotlin/claudechat/cli/ClaudeProcessTest.kt`

**Interfaces:**
- Consumes: `CliConnection` (Task 5).
- Produces: `class ClaudeProcess(command: List<String>, workDir: File, env: Map<String, String>) : CliConnection`. `start` throws `IOException` if the process cannot be spawned (ChatSession turns that into a DEAD notice).

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/cli/ClaudeProcessTest.kt`:
```kotlin
package claudechat.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaudeProcessTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"))

    @Test fun echoesStdinLinesBackOnStdout() {
        val lines = CopyOnWriteArrayList<String>()
        val got = CountDownLatch(2)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "while IFS= read -r l; do echo \"echo:\$l\"; done"), tmp, emptyMap())
        p.start(onLine = { lines += it; got.countDown() }, onExit = { _, _ -> })
        p.send("""{"a":1}""")
        p.send("two")
        assertTrue(got.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("""echo:{"a":1}""", "echo:two"), lines.toList())
        p.stop()
    }

    @Test fun reportsExitCodeAndStderrTail() {
        val lines = CopyOnWriteArrayList<String>()
        val exited = CountDownLatch(1)
        var code = -1
        var tail = ""
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "echo out; echo err1 >&2; echo err2 >&2; exit 3"), tmp, mapOf("X" to "1"))
        p.start(onLine = { lines += it }, onExit = { c, t -> code = c; tail = t; exited.countDown() })
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("out"), lines.toList())
        assertEquals(3, code)
        assertEquals("err1\nerr2", tail)
    }

    @Test fun sendAfterExitDoesNotThrow() {
        val exited = CountDownLatch(1)
        val p = ClaudeProcess(listOf("/bin/sh", "-c", "exit 0"), tmp, emptyMap())
        p.start(onLine = {}, onExit = { _, _ -> exited.countDown() })
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        p.send("x")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.cli.ClaudeProcessTest'`
Expected: compilation FAILS (`Unresolved reference: ClaudeProcess`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/cli/ClaudeProcess.kt`:
```kotlin
package claudechat.cli

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the claude CLI and exchanges NDJSON over its stdin/stdout. */
class ClaudeProcess(
    private val command: List<String>,
    private val workDir: File,
    private val env: Map<String, String>,
) : CliConnection {
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    override fun start(onLine: (String) -> Unit, onExit: (exitCode: Int, stderrTail: String) -> Unit) {
        val builder = ProcessBuilder(command).directory(workDir)
        builder.environment().putAll(env)
        val p = builder.start()
        process = p
        writer = p.outputStream.bufferedWriter(Charsets.UTF_8)

        val tail = ArrayDeque<String>()
        val stderrThread = thread(isDaemon = true, name = "claude-stderr") {
            p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > 40) tail.removeFirst()
                }
            }
        }
        thread(isDaemon = true, name = "claude-stdout") {
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
            } catch (_: IOException) {
                // stream closed by stop()
            }
            val code = p.waitFor()
            stderrThread.join(1000)
            onExit(code, synchronized(tail) { tail.joinToString("\n") })
        }
    }

    @Synchronized
    override fun send(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (_: IOException) {
            // process is gone; the exit callback reports it
        }
    }

    override fun stop() {
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        val p = process ?: return
        p.destroy()
        thread(isDaemon = true, name = "claude-reaper") {
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.cli.ClaudeProcessTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add ClaudeProcess transport"
```

---

### Task 7: Past sessions and transcripts

**Files:**
- Create: `src/main/kotlin/claudechat/session/SessionStore.kt`
- Test: `src/test/kotlin/claudechat/session/SessionStoreTest.kt`

**Interfaces:**
- Consumes: `ChatItem` (Task 4), JSON helpers (Task 2).
- Produces:
  - `data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long)`
  - `class SessionStore(claudeHome: File) { fun projectDir(cwd: String): File; fun list(cwd: String, limit: Int = 30): List<SessionSummary>; fun transcript(cwd: String, sessionId: String): List<ChatItem>; companion object { fun default(): SessionStore } }`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/session/SessionStoreTest.kt`:
```kotlin
package claudechat.session

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cwd = "/Users/me/my_proj.v2"

    private fun write(store: SessionStore, id: String, modified: Long, vararg lines: String): File {
        val dir = store.projectDir(cwd).apply { mkdirs() }
        return File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n")); setLastModified(modified) }
    }

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(text)}},"sessionId":"s"}"""
    private fun meta(text: String) = """{"type":"user","isMeta":true,"message":{"role":"user","content":"$text"}}"""
    private fun assistant(text: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""
    private val toolResult = """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"x"}]}}"""

    @Test fun projectDirEncodesCwd() {
        val store = SessionStore(tmp.root)
        assertEquals(File(tmp.root, "projects/-Users-me-my-proj-v2"), store.projectDir(cwd))
    }

    @Test fun listsNewestFirstWithFirstRealUserMessageAsTitle() {
        val store = SessionStore(tmp.root)
        write(store, "old", 1_000_000, """{"type":"session-owner"}""", user("first question"))
        write(store, "new", 2_000_000, meta("<command-name>"), user("<local-command>"), user("second\nquestion"))
        write(store, "empty", 3_000_000, """{"type":"session-owner"}""")
        assertEquals(
            listOf(SessionSummary("new", "second question", 2_000_000), SessionSummary("old", "first question", 1_000_000)),
            store.list(cwd))
    }

    @Test fun listOfUnknownProjectIsEmpty() = assertEquals(emptyList<SessionSummary>(), SessionStore(tmp.root).list("/nowhere"))

    @Test fun transcriptKeepsUserAndAssistantTextAndStripsIdeNote() {
        val store = SessionStore(tmp.root)
        write(store, "s1", 1, user("hello"), assistant("hi there"), toolResult, user("[IDE] Since your last turn...\nRe-read.\n\nnext one"), assistant("ok"))
        assertEquals(
            listOf(
                ChatItem.UserText("h-0", "hello"),
                ChatItem.AssistantText("h-1", "hi there", false),
                ChatItem.UserText("h-2", "next one"),
                ChatItem.AssistantText("h-3", "ok", false),
            ),
            store.transcript(cwd, "s1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.session.SessionStoreTest'`
Expected: compilation FAILS (`Unresolved reference: SessionStore`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/session/SessionStore.kt`:
```kotlin
package claudechat.session

import claudechat.cli.bool
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

data class SessionSummary(val id: String, val title: String, val modifiedMillis: Long)

/** Reads Claude Code's on-disk session transcripts (~/.claude/projects/<encoded cwd>/<id>.jsonl). */
class SessionStore(private val claudeHome: File) {
    fun projectDir(cwd: String): File = File(File(claudeHome, "projects"), cwd.replace(NON_ALNUM, "-"))

    fun list(cwd: String, limit: Int = 30): List<SessionSummary> =
        projectDir(cwd).listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> firstUserText(f)?.let { SessionSummary(f.nameWithoutExtension, oneLine(it), f.lastModified()) } }
            .take(limit)
            .toList()

    fun transcript(cwd: String, sessionId: String): List<ChatItem> {
        val file = File(projectDir(cwd), "$sessionId.jsonl")
        if (!file.isFile) return emptyList()
        val items = mutableListOf<ChatItem>()
        file.useLines { lines ->
            for (line in lines) {
                val obj = parseJsonObject(line) ?: continue
                when (obj.str("type")) {
                    "user" -> userText(obj)?.let { items += ChatItem.UserText("h-${items.size}", it) }
                    "assistant" -> assistantText(obj)?.let { items += ChatItem.AssistantText("h-${items.size}", it, false) }
                }
            }
        }
        return items
    }

    private fun firstUserText(file: File): String? = file.useLines { lines ->
        lines.mapNotNull { parseJsonObject(it) }.filter { it.str("type") == "user" }.mapNotNull { userText(it) }.firstOrNull()
    }

    private fun userText(obj: JsonObject): String? {
        if (obj.bool("isMeta") == true || obj.bool("isSidechain") == true) return null
        val text = textOf(obj.obj("message")?.get("content")) ?: return null
        if (text.startsWith("<") || text.startsWith("[Request interrupted")) return null
        return stripIdeNote(text)
    }

    private fun assistantText(obj: JsonObject): String? {
        if (obj.bool("isSidechain") == true) return null
        return textOf(obj.obj("message")?.get("content"))
    }

    private fun textOf(content: Any?): String? = when (content) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.filterIsInstance<JsonObject>()
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun stripIdeNote(text: String): String? =
        if (text.startsWith("[IDE] ")) text.substringAfter("\n\n", "").takeIf { it.isNotBlank() } else text

    private fun oneLine(text: String) = text.replace(Regex("\\s+"), " ").trim().take(80)

    companion object {
        private val NON_ALNUM = Regex("[^A-Za-z0-9]")

        fun default(): SessionStore {
            val home = System.getenv("CLAUDE_CONFIG_DIR")?.let(::File) ?: File(System.getProperty("user.home"), ".claude")
            return SessionStore(home)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.session.SessionStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "List past Claude sessions and load transcripts"
```

---

### Task 8: EditTracker

**Files:**
- Create: `src/main/kotlin/claudechat/ide/EditTracker.kt`
- Test: `src/test/kotlin/claudechat/ide/EditTrackerTest.kt`

**Interfaces:**
- Produces: `@Service(PROJECT) class EditTracker(project: Project) : Disposable { fun record(file: VirtualFile); fun consumeNote(): String?; fun clear(); companion object { fun getInstance(project: Project): EditTracker } }`. `consumeNote()` must run on the EDT (it saves all documents). Note format:
  `[IDE] Since your last turn the user edited these files: a.ts, src/b.ts.\nRe-read them before relying on earlier contents.`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/ide/EditTrackerTest.kt`:
```kotlin
package claudechat.ide

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditTrackerTest : BasePlatformTestCase() {
    private val tracker get() = EditTracker.getInstance(project)

    override fun setUp() {
        super.setUp()
        tracker.consumeNote() // light project is shared between tests: start clean
    }

    fun testUnsavedEditIsReportedOnce() {
        myFixture.configureByText("a.ts", "let x = 1;")
        myFixture.type("2")
        val note = tracker.consumeNote()
        assertEquals(
            "[IDE] Since your last turn the user edited these files: a.ts.\nRe-read them before relying on earlier contents.",
            note)
        assertNull(tracker.consumeNote())
    }

    fun testNoEditsMeansNoNote() {
        myFixture.configureByText("b.ts", "x")
        assertNull(tracker.consumeNote())
    }

    fun testExternalChangeIsNotReported() {
        val file = myFixture.configureByText("c.ts", "a").virtualFile
        WriteAction.run<Exception> { file.setBinaryContent("b".toByteArray()) }
        assertNull(tracker.consumeNote())
    }

    fun testClearDropsRecordedEdits() {
        myFixture.configureByText("d.ts", "x")
        myFixture.type("y")
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        tracker.clear()
        assertNull(tracker.consumeNote())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ide.EditTrackerTest'`
Expected: compilation FAILS (`Unresolved reference: EditTracker`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/ide/EditTracker.kt`:
```kotlin
package claudechat.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Records project files the user saved from the IDE. Claude's own writes are disk changes that the IDE
 * reloads (never saves), so they are not recorded.
 */
@Service(Service.Level.PROJECT)
class EditTracker(private val project: Project) : Disposable {
    private val edited = LinkedHashSet<String>()

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    FileDocumentManager.getInstance().getFile(document)?.let(::record)
                }
            })
    }

    fun record(file: VirtualFile) {
        if (project.isDisposed) return
        val relative = ReadAction.compute<String?, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            if (!index.isInContent(file)) null
            else index.getContentRootForFile(file)?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        } ?: return
        synchronized(edited) { edited += relative }
    }

    /** Saves all documents (recording unsaved user edits), returns the note for the next message or null, and resets. EDT only. */
    fun consumeNote(): String? {
        FileDocumentManager.getInstance().saveAllDocuments()
        val files = synchronized(edited) { edited.toList().also { edited.clear() } }
        if (files.isEmpty()) return null
        return "[IDE] Since your last turn the user edited these files: ${files.joinToString(", ")}.\n" +
            "Re-read them before relying on earlier contents."
    }

    fun clear() = synchronized(edited) { edited.clear() }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): EditTracker = project.service()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.ide.EditTrackerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Track user-saved files to tell Claude about edits"
```

---

### Task 9: FileLinker

**Files:**
- Create: `src/main/kotlin/claudechat/ide/FileLinker.kt`
- Test: `src/test/kotlin/claudechat/ide/FileLinkerTest.kt`

**Interfaces:**
- Consumes: `FileDiff` (Task 5).
- Produces: `data class ResolvedPath(val target: String, val line: Int?)` (`target` is a VFS URL); `@Service(PROJECT) class FileLinker(project) { fun resolve(candidate: String): ResolvedPath?; fun open(target: String, line: Int?); fun showDiff(diff: FileDiff); companion object { fun getInstance(project: Project): FileLinker } }`. `open` accepts a VFS URL (contains `://`) or an absolute local path. Accepted candidate forms: `path`, `path:12`, `path:12:5`, `path:12-20`, `path#L12`, `path#L12-20`; relative paths resolve against content roots; only existing, non-directory files inside project content resolve.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/ide/FileLinkerTest.kt`:
```kotlin
package claudechat.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileLinkerTest : BasePlatformTestCase() {
    private val linker get() = FileLinker.getInstance(project)

    fun testResolvesRelativePathWithLineForms() {
        val file = myFixture.addFileToProject("src/foo.ts", "a\nb\nc\n").virtualFile
        assertEquals(ResolvedPath(file.url, null), linker.resolve("src/foo.ts"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts:2:5"))
        assertEquals(ResolvedPath(file.url, 2), linker.resolve("src/foo.ts#L2-3"))
    }

    fun testUnknownOrDirectoryDoesNotResolve() {
        myFixture.addFileToProject("src/bar.ts", "x")
        assertNull(linker.resolve("src/missing.ts"))
        assertNull(linker.resolve("src"))
        assertNull(linker.resolve(""))
    }

    fun testOpenNavigatesToFile() {
        val file = myFixture.addFileToProject("src/open.ts", "a\nb\n").virtualFile
        linker.open(file.url, 2)
        val editors = FileEditorManager.getInstance(project)
        assertEquals(file, editors.selectedFiles.first())
        assertEquals(1, editors.selectedTextEditor!!.caretModel.logicalPosition.line)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ide.FileLinkerTest'`
Expected: compilation FAILS (`Unresolved reference: FileLinker`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/ide/FileLinker.kt`:
```kotlin
package claudechat.ide

import claudechat.session.FileDiff
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

data class ResolvedPath(val target: String, val line: Int?)

@Service(Service.Level.PROJECT)
class FileLinker(private val project: Project) {
    private val pattern = Regex("""^(.+?)(?:(?::|#L)(\d+)(?:[:-]\d+)?)?$""")

    fun resolve(candidate: String): ResolvedPath? {
        val match = pattern.matchEntire(candidate.trim()) ?: return null
        val raw = match.groupValues[1]
        val line = match.groupValues[2].toIntOrNull()
        val file = findFile(raw) ?: return null
        return ResolvedPath(file.url, line)
    }

    private fun findFile(raw: String): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
        val index = ProjectFileIndex.getInstance(project)
        val candidates = buildList {
            if (raw.startsWith("/")) LocalFileSystem.getInstance().findFileByPath(raw)?.let(::add)
            ProjectRootManager.getInstance(project).contentRoots.forEach { root ->
                root.findFileByRelativePath(raw.removePrefix("./"))?.let(::add)
            }
        }
        candidates.firstOrNull { !it.isDirectory && index.isInContent(it) }
    }

    /** [target] is a VFS URL or an absolute local path; [line] is 1-based. */
    fun open(target: String, line: Int?) {
        val file = findByTarget(target) ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    fun showDiff(diff: FileDiff) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath) ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Claude's edit: ${file.name}",
            factory.create(project, diff.originalContent, file.fileType),
            factory.create(project, file),
            "Before Claude's edit",
            "Current",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun findByTarget(target: String): VirtualFile? =
        if (target.contains("://")) VirtualFileManager.getInstance().findFileByUrl(target)
        else LocalFileSystem.getInstance().refreshAndFindFileByPath(target)

    companion object {
        fun getInstance(project: Project): FileLinker = project.service()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.ide.FileLinkerTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Resolve, open and diff files mentioned by Claude"
```

---

### Task 10: Settings and SessionManager

**Files:**
- Create: `src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt`, `src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt`, `src/main/kotlin/claudechat/session/SessionManager.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (register configurable)
- Test: `src/test/kotlin/claudechat/session/SessionManagerTest.kt`

**Interfaces:**
- Consumes: `ChatSession`, `ChatListener`, `SessionState`, `CliConnection` (Task 5); `ClaudeProcess` (Task 6); `ClaudeCommand`, `ClaudeBinary`, `PermissionMode`, `SessionLaunch` (Task 3); `SessionStore`, `SessionSummary` (Task 7); `EditTracker` (Task 8).
- Produces:
  - `@Service(APP) class ClaudeChatSettings : SimplePersistentStateComponent<ClaudeChatSettings.State>` with `State.claudePath`, `State.defaultMode`, `State.extraArgs` (all `String?`), `companion fun getInstance()`.
  - `interface SessionView { fun reset(items: List<ChatItem>); fun item(item: ChatItem); fun state(state: SessionState, sessionId: String?, mode: PermissionMode?); fun insert(text: String) }`
  - `@Service(PROJECT) class SessionManager(project) : Disposable` with `var connector: (SessionLaunch) -> CliConnection`, `var store: SessionStore`, `val current: ChatSession?`, `attach(view)`, `detach(view)`, `newChat()`, `resume(sessionId)`, `send(text)` (EDT), `listSessions(): List<SessionSummary>`, `insert(text)`, `companion fun getInstance(project)`.
  - `class ClaudeNotFoundException : Exception`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/session/SessionManagerTest.kt`:
```kotlin
package claudechat.session

import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.testutil.FakeConnection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class SessionManagerTest : BasePlatformTestCase() {
    private val launches = mutableListOf<SessionLaunch>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var home: File
    private val manager get() = SessionManager.getInstance(project)

    private class RecordingView : SessionView {
        val resets = mutableListOf<List<ChatItem>>()
        val items = mutableListOf<ChatItem>()
        val states = mutableListOf<SessionState>()
        val inserted = mutableListOf<String>()
        override fun reset(items: List<ChatItem>) { resets += items }
        override fun item(item: ChatItem) { items += item }
        override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) { states += state }
        override fun insert(text: String) { inserted += text }
    }

    override fun setUp() {
        super.setUp()
        home = Files.createTempDirectory("claude-home").toFile()
        manager.connector = { launch -> launches += launch; FakeConnection().also { connections += it } }
        manager.store = SessionStore(home)
        manager.newChat()
    }

    override fun tearDown() {
        try { manager.current?.dispose(); home.deleteRecursively() } finally { super.tearDown() }
    }

    fun testAttachReplaysStateOfCurrentSession() {
        val view = RecordingView()
        manager.attach(view)
        assertEquals(listOf(emptyList<ChatItem>()), view.resets)
        assertEquals(SessionState.IDLE, view.states.last())
        manager.detach(view)
    }

    fun testSendPrefixesEditNote() {
        val view = RecordingView()
        manager.attach(view)
        myFixture.configureByText("a.ts", "x")
        myFixture.type("y")
        manager.send("hello")
        val content = connections.last().userMessages().single()
        assertTrue(content.startsWith("[IDE] Since your last turn the user edited these files: a.ts."))
        assertTrue(content.endsWith("\n\nhello"))
        assertEquals("hello", (view.items.last { it is ChatItem.UserText } as ChatItem.UserText).text)
        manager.detach(view)
    }

    fun testNewChatStopsPreviousConnection() {
        val first = connections.last()
        manager.newChat()
        assertTrue(first.stopped)
        assertNotSame(first, connections.last())
    }

    fun testResumeLoadsHistoryAndPassesResumeId() {
        val dir = manager.store.projectDir(project.basePath!!).apply { mkdirs() }
        File(dir, "abc.jsonl").writeText("""{"type":"user","message":{"role":"user","content":"earlier"}}""")
        val view = RecordingView()
        manager.attach(view)
        manager.resume("abc")
        assertEquals("abc", launches.last().resumeId)
        assertEquals(listOf(ChatItem.UserText("h-0", "earlier")), view.resets.last())
        assertEquals(listOf("abc"), manager.listSessions().map { it.id })
        manager.detach(view)
    }

    fun testInsertBeforeAttachIsDeliveredOnAttach() {
        manager.insert("@src/a.ts#L1-2")
        val view = RecordingView()
        manager.attach(view)
        assertEquals(listOf("@src/a.ts#L1-2"), view.inserted)
        manager.detach(view)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.session.SessionManagerTest'`
Expected: compilation FAILS (`Unresolved reference: SessionManager`).

- [ ] **Step 3: Implement settings**

`src/main/kotlin/claudechat/settings/ClaudeChatSettings.kt`:
```kotlin
package claudechat.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "ClaudeChatSettings", storages = [Storage("claudeChat.xml")])
class ClaudeChatSettings : SimplePersistentStateComponent<ClaudeChatSettings.State>(State()) {
    class State : BaseState() {
        /** Empty = auto-detect. */
        var claudePath by string("")
        /** Empty = whatever the CLI's own settings say. */
        var defaultMode by string("")
        var extraArgs by string("")
    }

    companion object {
        fun getInstance(): ClaudeChatSettings = service()
    }
}
```

`src/main/kotlin/claudechat/settings/ClaudeChatConfigurable.kt`:
```kotlin
package claudechat.settings

import claudechat.cli.PermissionMode
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class ClaudeChatConfigurable : BoundConfigurable("Claude Chat") {
    private val state get() = ClaudeChatSettings.getInstance().state

    override fun createPanel(): DialogPanel = panel {
        row("Claude binary:") {
            textField()
                .bindText({ state.claudePath.orEmpty() }, { state.claudePath = it })
                .align(AlignX.FILL)
                .comment("Leave empty to auto-detect (PATH, ~/.local/bin, /opt/homebrew/bin, /usr/local/bin).")
        }
        row("Default permission mode:") {
            comboBox(listOf("") + PermissionMode.entries.map { it.cli })
                .bindItem({ state.defaultMode.orEmpty() }, { state.defaultMode = it.orEmpty() })
                .comment("Empty uses the CLI's own setting.")
        }
        row("Extra CLI arguments:") {
            textField()
                .bindText({ state.extraArgs.orEmpty() }, { state.extraArgs = it })
                .align(AlignX.FILL)
                .comment("For example: --model sonnet")
        }
    }
}
```

In `plugin.xml`, inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
        <applicationConfigurable parentId="tools" id="claudechat.settings" displayName="Claude Chat"
                                 instance="claudechat.settings.ClaudeChatConfigurable"/>
```

- [ ] **Step 4: Implement SessionManager**

`src/main/kotlin/claudechat/session/SessionManager.kt`:
```kotlin
package claudechat.session

import claudechat.cli.ClaudeBinary
import claudechat.cli.ClaudeCommand
import claudechat.cli.ClaudeProcess
import claudechat.cli.CliConnection
import claudechat.cli.PermissionMode
import claudechat.cli.SessionLaunch
import claudechat.ide.EditTracker
import claudechat.settings.ClaudeChatSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File

/** Receives chat updates. Methods may be called from any thread. */
interface SessionView {
    fun reset(items: List<ChatItem>)
    fun item(item: ChatItem)
    fun state(state: SessionState, sessionId: String?, mode: PermissionMode?)
    fun insert(text: String)
}

class ClaudeNotFoundException : Exception(
    "Claude Code CLI not found. Install it, run `claude` once in a terminal to log in, " +
        "or set its path in Settings > Tools > Claude Chat."
)

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) : Disposable {
    var connector: (SessionLaunch) -> CliConnection = ::defaultConnect
    var store: SessionStore = SessionStore.default()
    var current: ChatSession? = null
        private set

    @Volatile private var view: SessionView? = null
    private var pendingInsert: String? = null
    private val items = LinkedHashMap<String, ChatItem>()

    private val listener = object : ChatListener {
        override fun onItem(item: ChatItem) {
            synchronized(items) { items[item.id] = item }
            view?.item(item)
        }
        override fun onState(state: SessionState, sessionId: String?, mode: PermissionMode?) {
            view?.state(state, sessionId, mode)
        }
    }

    init {
        EditTracker.getInstance(project) // start recording saves before the first message
    }

    fun attach(view: SessionView) {
        this.view = view
        val session = current
        if (session == null) {
            newChat()
        } else {
            view.reset(synchronized(items) { items.values.toList() })
            view.state(session.state, session.sessionId, session.mode)
        }
        pendingInsert?.let { view.insert(it); pendingInsert = null }
    }

    fun detach(view: SessionView) {
        if (this.view === view) this.view = null
    }

    fun newChat() = open(resumeId = null, history = emptyList())

    fun resume(sessionId: String) = open(sessionId, store.transcript(cwd(), sessionId))

    /** EDT only (saves documents to compute the edit note). */
    fun send(text: String) {
        val session = current ?: return
        session.send(text, EditTracker.getInstance(project).consumeNote())
    }

    fun listSessions(): List<SessionSummary> = store.list(cwd())

    fun insert(text: String) {
        val v = view
        if (v != null) v.insert(text) else pendingInsert = text
    }

    private fun open(resumeId: String?, history: List<ChatItem>) {
        current?.dispose()
        EditTracker.getInstance(project).clear()
        synchronized(items) {
            items.clear()
            history.forEach { items[it.id] = it }
        }
        view?.reset(history)
        val session = ChatSession(connector, listener, resumeId, defaultMode(), log = { LOG.info(it) })
        current = session
        session.start()
    }

    private fun defaultMode(): PermissionMode? = PermissionMode.fromCli(ClaudeChatSettings.getInstance().state.defaultMode)

    private fun cwd(): String = project.basePath ?: System.getProperty("user.home")

    private fun defaultConnect(launch: SessionLaunch): CliConnection {
        val settings = ClaudeChatSettings.getInstance().state
        val env = EnvironmentUtil.getEnvironmentMap()
        val binary = ClaudeBinary.resolve(settings.claudePath.orEmpty(), env["PATH"], System.getProperty("user.home")) {
            File(it).canExecute()
        } ?: throw ClaudeNotFoundException()
        val command = ClaudeCommand.build(binary, launch, ParametersListUtil.parse(settings.extraArgs.orEmpty()))
        return ClaudeProcess(command, File(cwd()), env)
    }

    override fun dispose() {
        current?.dispose()
        current = null
    }

    companion object {
        private val LOG = logger<SessionManager>()
        fun getInstance(project: Project): SessionManager = project.service()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gw test --tests 'claudechat.session.SessionManagerTest'`
Expected: PASS.

- [ ] **Step 6: Run the full suite**

Run: `./gw test`
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Add settings and per-project SessionManager"
```

---

### Task 11: Web view assets

**Files:**
- Create: `src/main/resources/webview/index.html`, `src/main/resources/webview/chat.css`, `src/main/resources/webview/chat.js`, `src/main/resources/webview/vendor/marked.min.js`, `src/main/resources/webview/vendor/purify.min.js`, `src/main/kotlin/claudechat/ui/WebAssets.kt`
- Test: `src/test/kotlin/claudechat/ui/WebAssetsTest.kt`

**Interfaces:**
- Produces: `object WebAssets { fun page(): String }` — a single self-contained HTML page.
- Bridge contract (implemented by Task 12). JS → Kotlin via `window.__send(jsonString)` (injected after load, followed by a `bridge-ready` window event):
  `{type:"ready"}`, `{type:"send",text}`, `{type:"permission",requestId,decision:"allow"|"allowSession"|"deny",message?}`, `{type:"setMode",mode}`, `{type:"interrupt"}`, `{type:"newChat"}`, `{type:"restart"}`, `{type:"listSessions"}`, `{type:"resume",sessionId}`, `{type:"openFile",target,line}`, `{type:"openDiff",toolUseId}`, `{type:"openUrl",url}`, `{type:"resolvePaths",reqId,candidates:[...]}`.
  Kotlin → JS via `window.chat.receive(obj)`: `{type:"reset",items}`, `{type:"item",item}`, `{type:"state",state,sessionId,mode}`, `{type:"sessions",sessions:[{id,title,modified}]}`, `{type:"resolved",reqId,paths:{candidate:{target,line}}}`, `{type:"insert",text}`, `{type:"theme",bg,fg,muted,border,accent,codeBg,font,fontSize,dark}`.

- [ ] **Step 1: Vendor the libraries**

```bash
mkdir -p src/main/resources/webview/vendor
curl -fsSL https://cdn.jsdelivr.net/npm/marked@15.0.12/marked.min.js -o src/main/resources/webview/vendor/marked.min.js
curl -fsSL https://cdn.jsdelivr.net/npm/dompurify@3.2.6/dist/purify.min.js -o src/main/resources/webview/vendor/purify.min.js
head -c 200 src/main/resources/webview/vendor/marked.min.js; echo; head -c 200 src/main/resources/webview/vendor/purify.min.js
```
Expected: both files start with a license comment naming marked v15.0.12 and DOMPurify 3.2.6.

- [ ] **Step 2: Write the failing test**

`src/test/kotlin/claudechat/ui/WebAssetsTest.kt`:
```kotlin
package claudechat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAssetsTest {
    @Test fun pageInlinesEverything() {
        val page = WebAssets.page()
        assertFalse(page.contains("/*INLINE:"))
        assertTrue(page.contains("window.chat ="))
        assertTrue(page.contains("DOMPurify"))
        assertTrue(page.contains("marked"))
        // only the three closing script tags we wrote may appear
        assertTrue(Regex("</script").findAll(page).count() == 3)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ui.WebAssetsTest'`
Expected: compilation FAILS (`Unresolved reference: WebAssets`).

- [ ] **Step 4: Write the page**

`src/main/resources/webview/index.html`:
```html
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>/*INLINE:chat.css*/</style>
</head>
<body>
<header id="bar">
  <button id="new-chat" title="Start a new chat">New chat</button>
  <select id="sessions" title="Resume a past session"><option value="">History…</option></select>
  <select id="mode" title="Permission mode">
    <option value="default">Ask before edits</option>
    <option value="acceptEdits">Accept edits</option>
    <option value="plan">Plan only</option>
    <option value="auto">Auto</option>
  </select>
  <span id="status"></span>
  <button id="stop" hidden>Stop</button>
  <button id="restart" hidden>Restart</button>
</header>
<main id="log"></main>
<footer id="composer">
  <textarea id="input" rows="3" placeholder="Message Claude (Enter to send, Shift+Enter for a new line)"></textarea>
  <button id="send">Send</button>
</footer>
<script>/*INLINE:marked*/</script>
<script>/*INLINE:purify*/</script>
<script>/*INLINE:chat.js*/</script>
</body>
</html>
```

`src/main/resources/webview/chat.css`:
```css
:root {
  --bg: #1e1f22; --fg: #dfe1e5; --muted: #8c8f94; --border: #393b40; --accent: #589df6;
  --code-bg: #2b2d30; --add: #2f5a3a; --del: #5a2f2f; --danger: #c75450;
  --font: system-ui, sans-serif; --font-size: 13px;
}
* { box-sizing: border-box; }
html, body { height: 100%; margin: 0; }
body { display: flex; flex-direction: column; background: var(--bg); color: var(--fg); font-family: var(--font); font-size: var(--font-size); }
#bar { display: flex; gap: 6px; align-items: center; padding: 6px 8px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
#status { color: var(--muted); margin-left: auto; }
#log { flex: 1; overflow-y: auto; padding: 8px 10px; }
#composer { display: flex; gap: 6px; padding: 8px; border-top: 1px solid var(--border); }
#input { flex: 1; resize: vertical; min-height: 3em; background: var(--code-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 4px; padding: 6px; font: inherit; }
button, select { background: var(--code-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 4px; padding: 3px 8px; font: inherit; cursor: pointer; }
button:disabled, textarea:disabled { opacity: .5; cursor: default; }
button.link { background: none; border: none; color: var(--accent); padding: 0 4px; }
.msg { margin: 8px 0; line-height: 1.45; overflow-wrap: anywhere; }
.msg.user { background: var(--code-bg); border-radius: 6px; padding: 6px 10px; white-space: pre-wrap; }
.msg.assistant p { margin: .4em 0; }
pre { background: var(--code-bg); padding: 6px 8px; border-radius: 4px; overflow-x: auto; white-space: pre-wrap; margin: 4px 0; }
code { font-family: ui-monospace, Menlo, monospace; font-size: .95em; }
a { color: var(--accent); text-decoration: none; cursor: pointer; }
a:hover { text-decoration: underline; }
.tool { border-left: 3px solid var(--border); padding: 2px 8px; }
.tool.status-awaiting_permission { border-left-color: var(--accent); }
.tool.status-error, .tool.status-denied { border-left-color: var(--danger); }
.tool-head { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.tool-name { font-weight: 600; }
.tool-summary { color: var(--muted); font-family: ui-monospace, Menlo, monospace; }
.badge { color: var(--muted); font-size: .9em; }
pre.add { background: var(--add); }
pre.del { background: var(--del); }
.perm { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; margin: 6px 0; }
.perm input { flex: 1; min-width: 8em; background: var(--code-bg); color: var(--fg); border: 1px solid var(--border); border-radius: 4px; padding: 3px 6px; font: inherit; }
.result { color: var(--muted); font-size: .9em; }
.result.error, .notice.error { color: var(--danger); white-space: pre-wrap; }
.notice { color: var(--muted); font-style: italic; white-space: pre-wrap; }
details summary { cursor: pointer; color: var(--muted); }
```

`src/main/resources/webview/chat.js`:
```js
(function () {
  'use strict';
  const items = new Map(); // id -> element
  const log = document.getElementById('log');
  const input = document.getElementById('input');
  const sendBtn = document.getElementById('send');
  const modeSel = document.getElementById('mode');
  const sessionsSel = document.getElementById('sessions');
  const stopBtn = document.getElementById('stop');
  const restartBtn = document.getElementById('restart');
  const statusEl = document.getElementById('status');
  const pendingResolve = new Map();
  let reqSeq = 0;

  const STATUS = { IDLE: 'Ready', RUNNING: 'Working…', AWAITING_PERMISSION: 'Waiting for your permission', DEAD: 'Stopped' };
  const TOOL_STATUS = { running: 'running', awaiting_permission: 'needs permission', done: 'done', error: 'failed', denied: 'denied' };
  const PATH_RE = /^[\w@.\/~-]*[\w-]\.[A-Za-z0-9]{1,10}(?:(?::|#L)\d+(?:[:-]\d+)?)?$/;
  const MAX_PRE = 6000;

  function send(msg) { if (window.__send) window.__send(JSON.stringify(msg)); }

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v; else el.setAttribute(k, v === true ? '' : String(v));
    }
    for (const c of children) if (c !== null && c !== undefined) el.append(c instanceof Node ? c : String(c));
    return el;
  }
  const clip = (s) => (s && s.length > MAX_PRE ? s.slice(0, MAX_PRE) + '\n…' : s || '');
  const shortPath = (p) => p.split('/').slice(-2).join('/');

  window.chat = {
    receive(msg) {
      switch (msg.type) {
        case 'reset': items.clear(); log.textContent = ''; msg.items.forEach(upsert); break;
        case 'item': upsert(msg.item); break;
        case 'state': setState(msg); break;
        case 'sessions': fillSessions(msg.sessions); break;
        case 'resolved': { const cb = pendingResolve.get(msg.reqId); pendingResolve.delete(msg.reqId); if (cb) cb(msg.paths); break; }
        case 'insert': insertText(msg.text); break;
        case 'theme': applyTheme(msg); break;
      }
    }
  };

  function upsert(item) {
    const atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    let el = items.get(item.id);
    if (!el) { el = document.createElement('div'); items.set(item.id, el); log.appendChild(el); }
    const open = [...el.querySelectorAll('details')].map((d) => d.open);
    el.textContent = '';
    switch (item.kind) {
      case 'user': el.className = 'msg user'; el.textContent = item.text; break;
      case 'assistant': renderAssistant(el, item); break;
      case 'tool': renderTool(el, item); break;
      case 'result': renderResult(el, item); break;
      case 'notice': el.className = 'msg notice' + (item.isError ? ' error' : ''); el.textContent = item.text; break;
    }
    el.querySelectorAll('details').forEach((d, i) => { if (open[i] !== undefined) d.open = open[i]; });
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function renderAssistant(el, item) {
    el.className = 'msg assistant';
    el.innerHTML = DOMPurify.sanitize(marked.parse(item.text || ''));
    if (!item.streaming) linkify(el);
  }

  function linkify(root) {
    const codes = [...root.querySelectorAll('code')].filter((c) => !c.closest('pre') && PATH_RE.test(c.textContent.trim()));
    if (!codes.length) return;
    const reqId = ++reqSeq;
    pendingResolve.set(reqId, (paths) => {
      for (const c of codes) {
        const r = paths[c.textContent.trim()];
        if (!r || !c.isConnected) continue;
        const a = h('a', { class: 'file', href: '#', 'data-target': r.target, 'data-line': r.line });
        c.replaceWith(a);
        a.appendChild(c);
      }
    });
    send({ type: 'resolvePaths', reqId, candidates: [...new Set(codes.map((c) => c.textContent.trim()))] });
  }

  function summary(t) {
    const i = t.input || {};
    if (t.name === 'Bash') return (i.command || '').split('\n')[0];
    return i.pattern || i.url || i.description || i.query || '';
  }

  function inputDetails(t) {
    const i = t.input || {};
    const open = t.status === 'awaiting_permission';
    let body;
    if (t.name === 'Edit') body = [h('pre', { class: 'del' }, clip(i.old_string)), h('pre', { class: 'add' }, clip(i.new_string))];
    else if (t.name === 'MultiEdit') body = (i.edits || []).flatMap((e) => [h('pre', { class: 'del' }, clip(e.old_string)), h('pre', { class: 'add' }, clip(e.new_string))]);
    else if (t.name === 'Write') body = [h('pre', { class: 'add' }, clip(i.content))];
    else if (t.name === 'Bash') body = [h('pre', {}, clip(i.command))];
    else if (Object.keys(i).length) body = [h('pre', {}, clip(JSON.stringify(i, null, 2)))];
    else return null;
    return h('details', { open }, h('summary', {}, 'Input'), ...body);
  }

  function permissionCard(t) {
    const id = t.permissionRequestId;
    return h('div', { class: 'perm' },
      h('span', {}, 'Allow ' + t.name + '?'),
      h('button', { 'data-action': 'allow', 'data-req': id }, 'Allow'),
      h('button', { 'data-action': 'allowSession', 'data-req': id }, 'Allow for session'),
      h('input', { class: 'deny-reason', placeholder: 'Reason (optional)' }),
      h('button', { 'data-action': 'deny', 'data-req': id }, 'Deny'));
  }

  function renderTool(el, t) {
    el.className = 'msg tool status-' + t.status;
    const head = h('div', { class: 'tool-head' }, h('span', { class: 'tool-name' }, t.name));
    if (t.filePath) head.append(h('a', { class: 'file', href: '#', 'data-target': t.filePath, title: t.filePath }, shortPath(t.filePath)));
    else { const s = summary(t); if (s) head.append(h('span', { class: 'tool-summary' }, s)); }
    head.append(h('span', { class: 'badge' }, TOOL_STATUS[t.status] || t.status));
    if (t.hasDiff) head.append(h('button', { class: 'link', 'data-action': 'diff', 'data-id': t.id }, 'View diff'));
    el.append(head);
    const details = inputDetails(t);
    if (details) el.append(details);
    if (t.status === 'awaiting_permission' && t.permissionRequestId) el.append(permissionCard(t));
    if (t.resultPreview) el.append(h('details', {}, h('summary', {}, 'Result'), h('pre', {}, clip(t.resultPreview))));
  }

  function renderResult(el, r) {
    el.className = 'msg result' + (r.isError ? ' error' : '');
    if (r.isError) { el.textContent = r.text || 'Error'; return; }
    const parts = [];
    if (r.durationMs != null) parts.push((r.durationMs / 1000).toFixed(1) + 's');
    if (r.costUsd != null) parts.push('$' + r.costUsd.toFixed(2));
    el.textContent = 'Done' + (parts.length ? ' · ' + parts.join(' · ') : '');
  }

  function setState(msg) {
    statusEl.textContent = STATUS[msg.state] || msg.state;
    const busy = msg.state === 'RUNNING' || msg.state === 'AWAITING_PERMISSION';
    stopBtn.hidden = !busy;
    restartBtn.hidden = msg.state !== 'DEAD';
    input.disabled = sendBtn.disabled = msg.state === 'DEAD';
    if (msg.mode) modeSel.value = msg.mode;
  }

  function fillSessions(sessions) {
    sessionsSel.textContent = '';
    sessionsSel.append(h('option', { value: '' }, sessions.length ? 'History…' : 'No past sessions'));
    for (const s of sessions) {
      const when = new Date(s.modified).toLocaleString();
      sessionsSel.append(h('option', { value: s.id, title: when }, s.title));
    }
  }

  function insertText(text) {
    const start = input.selectionStart, end = input.selectionEnd;
    const before = input.value.slice(0, start), after = input.value.slice(end);
    const sep = before && !/\s$/.test(before) ? ' ' : '';
    input.value = before + sep + text + ' ' + after;
    const pos = (before + sep + text + ' ').length;
    input.setSelectionRange(pos, pos);
    input.focus();
  }

  function applyTheme(t) {
    const s = document.documentElement.style;
    s.setProperty('--bg', t.bg); s.setProperty('--fg', t.fg); s.setProperty('--muted', t.muted);
    s.setProperty('--border', t.border); s.setProperty('--accent', t.accent); s.setProperty('--code-bg', t.codeBg);
    s.setProperty('--font', '"' + t.font + '", system-ui, sans-serif'); s.setProperty('--font-size', t.fontSize + 'px');
    s.setProperty('--add', t.dark ? '#2f5a3a' : '#d7f5dd'); s.setProperty('--del', t.dark ? '#5a2f2f' : '#f9d7d7');
  }

  function submit() {
    const text = input.value.trim();
    if (!text || input.disabled) return;
    send({ type: 'send', text });
    input.value = '';
  }

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); submit(); }
  });
  sendBtn.addEventListener('click', submit);
  document.getElementById('new-chat').addEventListener('click', () => send({ type: 'newChat' }));
  stopBtn.addEventListener('click', () => send({ type: 'interrupt' }));
  restartBtn.addEventListener('click', () => send({ type: 'restart' }));
  modeSel.addEventListener('change', () => send({ type: 'setMode', mode: modeSel.value }));
  sessionsSel.addEventListener('mousedown', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('focus', () => send({ type: 'listSessions' }));
  sessionsSel.addEventListener('change', () => {
    const id = sessionsSel.value;
    sessionsSel.value = '';
    if (id) send({ type: 'resume', sessionId: id });
  });

  document.addEventListener('click', (e) => {
    const a = e.target.closest('a');
    if (a) {
      e.preventDefault();
      if (a.classList.contains('file')) send({ type: 'openFile', target: a.dataset.target, line: a.dataset.line ? Number(a.dataset.line) : null });
      else if (/^https?:/i.test(a.getAttribute('href') || '')) send({ type: 'openUrl', url: a.href });
      return;
    }
    const b = e.target.closest('button[data-action]');
    if (!b) return;
    const action = b.dataset.action;
    if (action === 'diff') send({ type: 'openDiff', toolUseId: b.dataset.id });
    else if (action === 'allow' || action === 'allowSession') send({ type: 'permission', requestId: b.dataset.req, decision: action });
    else if (action === 'deny') {
      const reason = b.parentElement.querySelector('.deny-reason').value.trim();
      send({ type: 'permission', requestId: b.dataset.req, decision: 'deny', message: reason || 'The user denied this action.' });
    }
  });

  window.addEventListener('bridge-ready', () => send({ type: 'ready' }));
})();
```

`src/main/kotlin/claudechat/ui/WebAssets.kt`:
```kotlin
package claudechat.ui

/** Builds the chat page as one self-contained HTML string from bundled resources. */
object WebAssets {
    private fun read(name: String): String =
        WebAssets::class.java.getResourceAsStream("/webview/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Inline scripts must not contain a literal closing script tag. */
    private fun script(name: String) = read(name).replace("</script", "<\\/script")

    fun page(): String = read("index.html")
        .replace("/*INLINE:chat.css*/", read("chat.css"))
        .replace("/*INLINE:marked*/", script("vendor/marked.min.js"))
        .replace("/*INLINE:purify*/", script("vendor/purify.min.js"))
        .replace("/*INLINE:chat.js*/", script("chat.js"))
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.ui.WebAssetsTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src
git commit -m "Add chat web view page"
```

---

### Task 12: Bridge, JCEF panel and tool window

**Files:**
- Create: `src/main/kotlin/claudechat/ui/Theme.kt`, `src/main/kotlin/claudechat/ui/ChatBridge.kt`, `src/main/kotlin/claudechat/ui/ChatPanel.kt`, `src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (tool window)
- Test: `src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`

**Interfaces:**
- Consumes: `SessionManager`, `SessionView` (Task 10); `FileLinker` (Task 9); `PermissionDecision`, `PermissionMode`, `ChatItem.toJson` (Tasks 3–5); `WebAssets` (Task 11); bridge contract from Task 11.
- Produces: `class ChatBridge(project: Project, post: (String) -> Unit) : SessionView { fun handle(json: String) }` (`handle` must run on the EDT); `object Theme { fun message(): String }`; `class ChatPanel(project, parent: Disposable)` exposing `browser: JBCefBrowser`; tool window id `Claude Chat`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/ui/ChatBridgeTest.kt`:
```kotlin
package claudechat.ui

import claudechat.cli.arr
import claudechat.cli.obj
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.session.SessionManager
import claudechat.testutil.FakeConnection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.serialization.json.JsonObject

class ChatBridgeTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonObject>()
    private val connections = mutableListOf<FakeConnection>()
    private lateinit var bridge: ChatBridge
    private val manager get() = SessionManager.getInstance(project)

    override fun setUp() {
        super.setUp()
        manager.connector = { FakeConnection().also { connections += it } }
        manager.newChat()
        bridge = ChatBridge(project) { posted += parseJsonObject(it)!! }
    }

    override fun tearDown() {
        try { manager.detach(bridge); manager.current?.dispose() } finally { super.tearDown() }
    }

    private fun types() = posted.map { it.str("type") }

    fun testReadySendsThemeResetAndState() {
        bridge.handle("""{"type":"ready"}""")
        assertEquals(listOf("theme", "reset", "state"), types())
        assertEquals("IDLE", posted.last().str("state"))
    }

    fun testSendReachesCliAndEchoesItem() {
        bridge.handle("""{"type":"ready"}""")
        bridge.handle("""{"type":"send","text":"hello"}""")
        assertEquals(listOf("hello"), connections.last().userMessages())
        val item = posted.last { it.str("type") == "item" }.obj("item")!!
        assertEquals("user", item.str("kind"))
        assertEquals("RUNNING", posted.last { it.str("type") == "state" }.str("state"))
    }

    fun testResolvePathsReturnsOnlyExistingFiles() {
        val file = myFixture.addFileToProject("src/x.ts", "a").virtualFile
        bridge.handle("""{"type":"resolvePaths","reqId":7,"candidates":["src/x.ts:1","nope.ts"]}""")
        val msg = posted.single()
        assertEquals("resolved", msg.str("type"))
        val paths = msg.obj("paths")!!
        assertEquals(file.url, paths.obj("src/x.ts:1")!!.str("target"))
        assertNull(paths["nope.ts"])
    }

    fun testListSessionsPostsSessions() {
        bridge.handle("""{"type":"listSessions"}""")
        assertEquals("sessions", posted.single().str("type"))
        assertNotNull(posted.single().arr("sessions"))
    }

    fun testUnknownOrMalformedMessagesAreIgnored() {
        bridge.handle("""{"type":"nope"}""")
        bridge.handle("not json")
        assertTrue(posted.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ui.ChatBridgeTest'`
Expected: compilation FAILS (`Unresolved reference: ChatBridge`).

- [ ] **Step 3: Implement Theme and ChatBridge**

`src/main/kotlin/claudechat/ui/Theme.kt`:
```kotlin
package claudechat.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Color

/** IDE colours and font for the web view. */
object Theme {
    private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    fun message(): String {
        val font = UIUtil.getLabelFont()
        return buildJsonObject {
            put("type", "theme")
            put("bg", hex(UIUtil.getPanelBackground()))
            put("fg", hex(UIUtil.getLabelForeground()))
            put("muted", hex(UIUtil.getContextHelpForeground()))
            put("border", hex(JBColor.border()))
            put("accent", hex(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            put("codeBg", hex(EditorColorsManager.getInstance().globalScheme.defaultBackground))
            put("font", font.family)
            put("fontSize", font.size)
            put("dark", !JBColor.isBright())
        }.toString()
    }
}
```

`src/main/kotlin/claudechat/ui/ChatBridge.kt`:
```kotlin
package claudechat.ui

import claudechat.cli.PermissionMode
import claudechat.cli.arr
import claudechat.cli.long
import claudechat.cli.parseJsonObject
import claudechat.cli.str
import claudechat.ide.FileLinker
import claudechat.session.ChatItem
import claudechat.session.PermissionDecision
import claudechat.session.SessionManager
import claudechat.session.SessionState
import claudechat.session.SessionSummary
import claudechat.session.SessionView
import claudechat.session.toJson
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Translates web-view messages into SessionManager/FileLinker calls and chat updates into web-view messages. */
class ChatBridge(private val project: Project, private val post: (String) -> Unit) : SessionView {
    private val manager get() = SessionManager.getInstance(project)
    private val linker get() = FileLinker.getInstance(project)

    /** EDT only. */
    fun handle(json: String) {
        val msg = parseJsonObject(json) ?: return
        when (msg.str("type")) {
            "ready" -> { post(Theme.message()); manager.attach(this) }
            "send" -> msg.str("text")?.takeIf { it.isNotBlank() }?.let(manager::send)
            "permission" -> {
                val requestId = msg.str("requestId") ?: return
                val decision = when (msg.str("decision")) {
                    "allow" -> PermissionDecision.Allow
                    "allowSession" -> PermissionDecision.AllowForSession
                    else -> PermissionDecision.Deny(msg.str("message") ?: "The user denied this action.")
                }
                manager.current?.respond(requestId, decision)
            }
            "setMode" -> PermissionMode.fromCli(msg.str("mode"))?.let { manager.current?.setPermissionMode(it) }
            "interrupt" -> manager.current?.interrupt()
            "newChat" -> manager.newChat()
            "restart" -> manager.current?.restart()
            "listSessions" -> post(sessionsMessage(manager.listSessions()))
            "resume" -> msg.str("sessionId")?.let(manager::resume)
            "openFile" -> msg.str("target")?.let { linker.open(it, msg.long("line")?.toInt()) }
            "openDiff" -> msg.str("toolUseId")?.let { id -> manager.current?.diffFor(id)?.let(linker::showDiff) }
            "openUrl" -> msg.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let(BrowserUtil::browse)
            "resolvePaths" -> post(resolvedMessage(msg.long("reqId") ?: 0, msg.arr("candidates").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }))
        }
    }

    override fun reset(items: List<ChatItem>) = post(buildJsonObject {
        put("type", "reset")
        put("items", JsonArray(items.map { it.toJson() }))
    }.toString())

    override fun item(item: ChatItem) = post(buildJsonObject {
        put("type", "item")
        put("item", item.toJson())
    }.toString())

    override fun state(state: SessionState, sessionId: String?, mode: PermissionMode?) = post(buildJsonObject {
        put("type", "state")
        put("state", state.name)
        put("sessionId", sessionId)
        put("mode", mode?.cli)
    }.toString())

    override fun insert(text: String) = post(buildJsonObject {
        put("type", "insert")
        put("text", text)
    }.toString())

    private fun sessionsMessage(sessions: List<SessionSummary>) = buildJsonObject {
        put("type", "sessions")
        put("sessions", JsonArray(sessions.map { s ->
            buildJsonObject { put("id", s.id); put("title", s.title); put("modified", s.modifiedMillis) }
        }))
    }.toString()

    private fun resolvedMessage(reqId: Long, candidates: List<String>) = buildJsonObject {
        put("type", "resolved")
        put("reqId", reqId)
        putJsonObject("paths") {
            for (candidate in candidates) {
                val resolved = linker.resolve(candidate) ?: continue
                put(candidate, buildJsonObject { put("target", resolved.target); put("line", resolved.line) })
            }
        }
    }.toString()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gw test --tests 'claudechat.ui.ChatBridgeTest'`
Expected: PASS.

- [ ] **Step 5: Implement the panel and tool window**

`src/main/kotlin/claudechat/ui/ChatPanel.kt`:
```kotlin
package claudechat.ui

import claudechat.session.SessionManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/** JCEF browser hosting the chat page, wired to a [ChatBridge]. */
class ChatPanel(private val project: Project, parent: Disposable) : Disposable {
    val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val bridge = ChatBridge(project, ::post)

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)

        query.addHandler { json ->
            ApplicationManager.getApplication().invokeLater({ bridge.handle(json) }, project.disposed)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val js = "window.__send = function(m) { ${query.inject("m")} }; window.dispatchEvent(new Event('bridge-ready'));"
                cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
        browser.loadHTML(WebAssets.page())
    }

    private fun post(json: String) {
        browser.cefBrowser.executeJavaScript("window.chat && window.chat.receive($json);", "", 0)
    }

    override fun dispose() {
        SessionManager.getInstance(project).detach(bridge)
    }
}
```

`src/main/kotlin/claudechat/ui/ChatToolWindowFactory.kt`:
```kotlin
package claudechat.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val component = if (JBCefApp.isSupported()) {
            ChatPanel(project, toolWindow.disposable).browser.component
        } else {
            JBLabel("Claude Chat needs JCEF, which is not available in this IDE runtime.")
        }
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(component, null, false))
    }

    companion object {
        const val ID = "Claude Chat"
    }
}
```

In `plugin.xml`, inside `<extensions defaultExtensionNs="com.intellij">`:
```xml
        <toolWindow id="Claude Chat" anchor="right" icon="AllIcons.Toolwindows.ToolWindowMessages"
                    factoryClass="claudechat.ui.ChatToolWindowFactory"/>
```

- [ ] **Step 6: Build and run the full suite**

Run: `./gw test buildPlugin`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src
git commit -m "Add Claude Chat tool window with JCEF bridge"
```

---

### Task 13: "Add to Claude chat" editor action

**Files:**
- Create: `src/main/kotlin/claudechat/ide/AddToChatAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (actions)
- Test: `src/test/kotlin/claudechat/ide/MentionTest.kt`

**Interfaces:**
- Consumes: `SessionManager.insert` (Task 10), `ChatToolWindowFactory.ID` (Task 12).
- Produces: `object Mention { fun format(path: String, startLine: Int?, endLine: Int?): String }` → `@path`, `@path#L5`, `@path#L5-9`; action id `claudechat.AddToChat`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/claudechat/ide/MentionTest.kt`:
```kotlin
package claudechat.ide

import org.junit.Assert.assertEquals
import org.junit.Test

class MentionTest {
    @Test fun noSelection() = assertEquals("@src/a.ts", Mention.format("src/a.ts", null, null))
    @Test fun singleLine() = assertEquals("@src/a.ts#L5", Mention.format("src/a.ts", 5, 5))
    @Test fun range() = assertEquals("@src/a.ts#L5-9", Mention.format("src/a.ts", 5, 9))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gw test --tests 'claudechat.ide.MentionTest'`
Expected: compilation FAILS (`Unresolved reference: Mention`).

- [ ] **Step 3: Implement**

`src/main/kotlin/claudechat/ide/AddToChatAction.kt`:
```kotlin
package claudechat.ide

import claudechat.session.SessionManager
import claudechat.ui.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager

object Mention {
    fun format(path: String, startLine: Int?, endLine: Int?): String = when {
        startLine == null -> "@$path"
        endLine == null || endLine == startLine -> "@$path#L$startLine"
        else -> "@$path#L$startLine-$endLine"
    }
}

/** Inserts an @-reference to the current file (and selected lines) into the chat input. */
class AddToChatAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        val path = root?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path

        val selection = editor.selectionModel
        val mention = if (selection.hasSelection()) {
            val doc = editor.document
            val start = selection.selectionStart
            val end = if (selection.selectionEnd > start) selection.selectionEnd - 1 else start
            Mention.format(path, doc.getLineNumber(start) + 1, doc.getLineNumber(end) + 1)
        } else {
            Mention.format(path, null, null)
        }

        val manager = SessionManager.getInstance(project)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ChatToolWindowFactory.ID)
        if (toolWindow == null) manager.insert(mention) else toolWindow.activate { manager.insert(mention) }
    }
}
```

In `plugin.xml`, after `</extensions>`:
```xml
    <actions>
        <action id="claudechat.AddToChat" class="claudechat.ide.AddToChatAction"
                text="Add to Claude Chat" description="Insert a reference to this file or selection into the Claude chat">
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
            <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift alt K"/>
            <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift alt K"/>
        </action>
    </actions>
```

- [ ] **Step 4: Run tests and build**

Run: `./gw test buildPlugin`
Expected: `BUILD SUCCESSFUL`, all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Add 'Add to Claude Chat' editor action"
```

---

### Task 14: End-to-end check in a sandbox WebStorm

**Files:** none (fix-ups only if a check fails; each fix gets a regression test where the logic is testable outside the UI).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Launch the sandbox IDE**

Run: `./gw runIde` (opens a sandbox WebStorm; open any small project, e.g. a scratch folder with one `.ts` file).

- [ ] **Step 2: Verify each requirement and note the result**

1. Open the **Claude Chat** tool window (right side). The status reads "Ready" and colours match the IDE theme.
2. Send "Summarise this project in two sentences." Text streams in, a "Done · Ns · $x" line ends the turn.
3. Send "Create notes.txt containing hello." With mode "Ask before edits", a permission card appears; click **Allow**. The tool row shows `notes.txt` as a link and **View diff**; both work (diff left side empty).
4. Edit `notes.txt` in the editor (don't save), then send "What does notes.txt contain now?" Claude's answer reflects your edit (the edit note was sent; it does not appear in your chat bubble).
5. Ask "Which file did you create? Answer with the path in backticks." The backticked path is clickable and opens the file.
6. Ask for a long answer and press **Stop**: an "Interrupted" notice appears and the status returns to "Ready".
7. Click **New chat**, then open **History…** and pick the earlier session: the previous messages appear and a follow-up question is answered with that context.
8. Select lines in an editor, press Cmd+Shift+Option+K: `@path#Lx-y` is inserted into the chat input.
9. In Settings > Tools > Claude Chat, set the binary path to `/nonexistent`, click **New chat**: a red notice explains the CLI was not found. Clear the setting afterwards.

- [ ] **Step 3: Fix any failures**

For each failing check, find the root cause (use superpowers:systematic-debugging), add a failing test when the cause is in non-UI code, fix, rerun `./gw test`, and commit with a message describing the fix.

- [ ] **Step 4: Final commit**

```bash
git status --short   # expect clean
```
