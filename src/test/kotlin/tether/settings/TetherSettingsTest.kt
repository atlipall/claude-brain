package tether.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class TetherSettingsTest {
    @Test fun followingIsOnByDefault() = assertTrue(TetherSettings.State().followCheckout)

    @Test fun editorContextIsOnByDefault() = assertTrue(TetherSettings.State().attachEditorContext)
}
