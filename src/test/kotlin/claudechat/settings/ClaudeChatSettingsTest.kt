package claudechat.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeChatSettingsTest {
    @Test fun followingIsOnByDefault() = assertTrue(ClaudeChatSettings.State().followCheckout)

    @Test fun editorContextIsOnByDefault() = assertTrue(ClaudeChatSettings.State().attachEditorContext)
}
