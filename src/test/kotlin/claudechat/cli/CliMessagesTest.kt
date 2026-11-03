package claudechat.cli

import claudechat.image.Image
import claudechat.image.ImageType
import claudechat.testutil.TestImages
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

    private val red = Image(ImageType.PNG, TestImages.bytes(TestImages.RED_PNG_BASE64), 4, 4)

    @Test fun userContentMatchesTheProbedShape() = assertEquals(
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"What color is this image? One word."},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}}]}}""",
        CliMessages.userContent("What color is this image? One word.", listOf(red)))

    @Test fun userContentWithoutTextHasOnlyImageBlocks() = assertEquals(
        """{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"${TestImages.RED_PNG_BASE64}"}}]}}""",
        CliMessages.userContent(" ", listOf(red, red)))

    @Test fun userContentWithoutImagesIsPlainText() = assertEquals(CliMessages.userText("hi"), CliMessages.userContent("hi", emptyList()))
}
