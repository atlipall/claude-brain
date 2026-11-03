package claudechat.cli

import claudechat.image.Image
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    /**
     * A user message with images: a content array of the text block (left out when [text] is blank) and one base64
     * image block per image, in that order. Without images it is [userText].
     */
    fun userContent(text: String, images: List<Image>): String {
        if (images.isEmpty()) return userText(text)
        return buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    if (text.isNotBlank()) addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                    for (image in images) addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", image.type.mime)
                            put("data", image.base64())
                        }
                    }
                }
            }
        }.toString()
    }

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
