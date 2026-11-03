package claudechat.testutil

import kotlinx.serialization.json.JsonPrimitive

/** Builders for CLI stdout lines, shaped like real claude 2.1.317 output (trimmed). */
object Fx {
    fun q(s: String): String = JsonPrimitive(s).toString()

    fun init(sessionId: String = "sess-1", mode: String = "default", cwd: String = "/proj") =
        """{"type":"system","subtype":"init","cwd":${q(cwd)},"session_id":"$sessionId","tools":["Read","Write"],"permissionMode":"$mode","claude_code_version":"2.1.317","uuid":"u1"}"""

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
