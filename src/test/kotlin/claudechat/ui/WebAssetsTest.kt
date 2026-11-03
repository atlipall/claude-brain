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

    @Test fun pageDeclaresContentSecurityPolicy() {
        val page = WebAssets.page()
        assertTrue(page.contains("Content-Security-Policy"))
        assertTrue(page.contains("img-src data:"))
    }

    @Test fun historyDropdownIsGone() {
        val page = WebAssets.page()
        assertFalse(page.contains("id=\"sessions\""))
        assertFalse(page.contains("listSessions"))
    }

    @Test fun pageShowsCheckoutAsPlainText() {
        val page = WebAssets.page()
        assertTrue(page.contains("<span id=\"checkout\" hidden></span>"))
        assertTrue(page.contains("case 'checkout': setCheckout(msg.text);"))
        assertTrue(page.contains("checkoutEl.textContent ="))
        assertFalse(page.contains("checkoutEl.innerHTML"))
    }

    @Test fun pageHandlesHistoryRecall() {
        val page = WebAssets.page()
        assertTrue(page.contains("case 'history':"))
        assertTrue(page.contains("ArrowUp"))
        assertTrue(page.contains("ArrowDown"))
    }

    @Test fun pageShowsTheContextChipAsPlainText() {
        val page = WebAssets.page()
        assertTrue(page.contains("<div id=\"context\" hidden><span id=\"context-text\"></span>"))
        assertTrue(page.contains("case 'context': setContext(msg.text, msg.off); break;"))
        assertTrue(page.contains("contextText.textContent ="))
        assertFalse(page.contains("contextText.innerHTML"))
        assertFalse(page.contains("contextEl.innerHTML"))
        assertTrue(page.contains("send({ type: 'contextOff', off: true })"))
        assertTrue(page.contains("send({ type: 'contextOff', off: false })"))
        assertTrue(page.contains("#context[hidden] { display: none; }"))
    }

    @Test fun pageSendsImagesToTheIdeInChunks() {
        val page = WebAssets.page()
        assertTrue(page.contains("<div id=\"attachments\" hidden></div>"))
        assertTrue(page.contains("<button id=\"attach\" title=\"Attach images\">📎</button>"))
        assertTrue(page.contains("const CHUNK = 262144;"))
        assertTrue(page.contains("send({ type: 'attachChunk', id, data: data.slice(i, i + CHUNK) });"))
        assertTrue(page.contains("send({ type: 'pickImages' })"))
        assertTrue(page.contains("case 'attachment': setAttachment(msg); break;"))
        assertTrue(page.contains("#attachments[hidden] { display: none; }"))
    }

    @Test fun droppedFilesNeverReachTheBrowser() {
        val page = WebAssets.page()
        assertTrue(page.contains("document.addEventListener('dragover', (e) => {"))
        assertTrue(page.contains("document.addEventListener('drop', (e) => {"))
        assertTrue(page.contains("    if (files && !input.disabled) [...e.dataTransfer.files].forEach(upload);\n  }, true);"))
        assertTrue(page.contains("document.addEventListener('paste', (e) => {"))
    }

    @Test fun imagesAreOnlyPageCreatedDataUrls() {
        val page = WebAssets.page()
        assertTrue(page.contains("""const DATA_IMG = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+\/]+=*$/;"""))
        assertTrue(page.contains("DATA_IMG.test(src) ? h('img', { src, alt })"))
        assertTrue(page.contains("'base', 'img', 'picture'")) // model markup still may not contain images
        assertTrue(page.contains("img-src data:;"))
        assertTrue(page.contains("if (item.text) el.append(document.createTextNode(item.text));"))
    }
}
