package claudechat.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeChatSettingsTest {
    @Test fun followingIsOnByDefault() = assertTrue(ClaudeChatSettings.State().followCheckout)
}
