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
