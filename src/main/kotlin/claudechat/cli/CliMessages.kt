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
