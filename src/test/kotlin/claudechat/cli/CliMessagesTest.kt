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
