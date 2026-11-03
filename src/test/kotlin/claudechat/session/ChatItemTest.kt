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
