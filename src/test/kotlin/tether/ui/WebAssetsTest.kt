package tether.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAssetsTest {
    /** Only the three closing script tags we wrote may appear. */
    @Test fun pageInlinesEverything() {
        val page = WebAssets.page()
        assertFalse(page.contains("/*INLINE:"))
        assertTrue(page.contains("window.chat ="))
        assertTrue(page.contains("DOMPurify"))
        assertTrue(page.contains("marked"))
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

    @Test fun theHeaderHasModelAndEffortPickersAndNoNewChatButton() {
        val page = WebAssets.page()
        assertFalse(page.contains("id=\"new-chat\""))
        assertFalse(page.contains("'newChat'"))
        assertTrue(page.contains("id=\"model\""))
        assertTrue(page.contains("id=\"effort\""))
        assertTrue(page.contains("type: 'setModel'"))
        assertTrue(page.contains("type: 'setEffort'"))
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

    @Test fun pageHasNoContextChip() {
        val page = WebAssets.page()
        assertFalse(page.contains("id=\"context\""))
        assertFalse(page.contains("#context"))
        assertFalse(page.contains("setContext"))
        assertFalse(page.contains("contextOff"))
        assertFalse(page.contains("case 'context'"))
    }

    /**
     * Images that decode or resolve late grow the content, so it is observed too. Following is a state, not
     * re-measured on each update.
     */
    @Test fun logFollowsWhenItShrinksOrItsContentGrows() {
        val page = WebAssets.page()
        assertTrue(page.contains("<main id=\"log\"><div id=\"log-content\"></div></main>"))
        assertTrue(page.contains("resized.observe(log);"))
        assertTrue(page.contains("resized.observe(logContent);"))
        assertFalse(page.contains("nearBottom"))
    }

    @Test fun logHasAJumpToBottomArrow() {
        val page = WebAssets.page()
        val wrap = page.substring(page.indexOf("<div id=\"log-wrap\">"), page.indexOf("<div id=\"queue\""))
        assertTrue(wrap.contains("<button id=\"to-bottom\" hidden"))
        assertTrue(page.contains("toBottom.addEventListener('click'"))
    }

    @Test fun turnResultShowsNoCost() = assertFalse(WebAssets.page().contains("r.costUsd"))

    @Test fun headerHasAContextUsageMeter() {
        val page = WebAssets.page()
        val header = page.substring(page.indexOf("<header id=\"bar\">"), page.indexOf("</header>"))
        assertTrue(header.contains("id=\"usage\" hidden"))
        assertTrue(page.contains("case 'usage': setUsage(msg)"))
        assertFalse("CLI category names stay plain text (title attribute)", page.contains("usageEl.innerHTML"))
    }

    @Test fun pageShowsWhatClaudeIsDoingAboveTheInput() {
        val page = WebAssets.page()
        val html = page.substring(page.indexOf("<main id=\"log\">"))
        assertTrue(html.indexOf("<div id=\"activity\" hidden>") in 0 until html.indexOf("<div id=\"attachments\""))
        assertTrue(html.indexOf("</main>") in 0 until html.indexOf("<div id=\"activity\""))
        assertFalse(page.contains("id=\"stop\""))
        assertFalse(page.contains("getElementById('stop')"))
        assertTrue(page.contains("<button id=\"restart\" hidden>Restart</button>"))
        assertTrue(page.contains("case 'activity': setActivity(msg); break;"))
        assertTrue(page.contains("document.getElementById('activity-stop')"))
        assertTrue(page.contains("activityStop.addEventListener('click', () => send({ type: 'interrupt' }));"))
        assertTrue(page.contains("activityLabel.textContent ="))
        assertTrue(page.contains("setInterval(tickActivity, 1000)"))
        assertTrue(page.contains("prefers-reduced-motion"))
        assertFalse(page.contains("activityLabel.innerHTML"))
        assertFalse(page.contains("activityEl.innerHTML"))
        assertFalse(page.contains("Waiting for your permission"))
    }

    @Test fun pageSendsImagesToTheIdeInChunks() {
        val page = WebAssets.page()
        assertTrue(page.contains("<div id=\"attachments\" hidden></div>"))
        assertTrue(page.contains("<button id=\"attach\" title=\"Attach images\" aria-label=\"Attach images\"><svg"))
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
        assertTrue(page.contains("if (e.target === input && e.clipboardData && e.clipboardData.getData('text/plain')) return;"))
    }

    @Test fun imagesAreOnlyPageCreatedDataUrls() {
        val page = WebAssets.page()
        assertTrue(page.contains("""const DATA_IMG = /^data:image\/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+\/]+=*$/;"""))
        assertTrue(page.contains("DATA_IMG.test(src) ? h('img', { src, alt })"))
        assertTrue("model markup still may not contain images", page.contains("'base', 'img', 'picture'"))
        assertTrue(page.contains("img-src data:;"))
        assertTrue(page.contains("if (item.text) el.append(document.createTextNode(item.text));"))
    }

    @Test fun pageShowsToolResultImagesAndBatchedHistory() {
        val page = WebAssets.page()
        assertTrue(page.contains("case 'items': msg.items.forEach(upsert); break;"))
        assertTrue(page.contains("h('div', { class: 'tool-images' }, ...images.map((src) => imageOrAlt(src, '[image]')))"))
        assertTrue(page.contains(".tool-images img { display: block; max-width: 100%; max-height: 400px;"))
    }

    @Test fun markdownImagesBecomeMarkersNotMarkup() {
        val page = WebAssets.page()
        assertTrue(page.contains("marked.use({ renderer: { image(token) {"))
        assertTrue(page.contains("const IMG_NONCE = Array.from(crypto.getRandomValues(new Uint32Array(2))"))
        assertTrue(page.contains("el.innerHTML = DOMPurify.sanitize(html, SANITIZE);"))
        assertTrue(page.contains("const images = placeImages(el, slots);"))
        assertTrue(page.contains("send({ type: 'resolveImages', reqId, paths: [...new Set(wanted.map((x) => imageKey(x.href)))] });"))
        assertTrue(page.contains("case 'images':"))
        assertTrue("remote and other URL schemes are never requested", page.contains("""!/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(x.href.trim())"""))
    }

    @Test fun questionCardShowsModelTextAsTextOnly() {
        val page = WebAssets.page()
        assertTrue(page.contains("function questionCard(t)"))
        assertTrue(page.contains("h('div', { class: 'q-text' }, q.question)"))
        assertTrue(page.contains("h('div', { class: 'q-header' }, q.header)"))
        assertTrue(page.contains("h('span', { class: 'q-label' }, o.label)"))
        assertTrue(page.contains("h('span', { class: 'q-desc' }, o.description)"))
        assertTrue(page.contains("preview.textContent = o.preview ? clip(o.preview) : '';"))
        assertTrue(page.contains("(x.header || x.question) + ': ' +"))
        val js = page.substring(page.indexOf("function questionCard(t)"), page.indexOf("function renderTool(el, t)"))
        assertFalse(js.contains("innerHTML"))
        assertFalse(js.contains("insertAdjacentHTML"))
        assertFalse(js.contains("marked.parse"))
        assertFalse(js.contains("setAttribute('on"))
        assertFalse(js.contains("allowSession"))
    }

    @Test fun questionAnswersGoThroughPageCreatedControls() {
        val page = WebAssets.page()
        assertTrue(page.contains("send({ type: 'answer', requestId: id, answers });"))
        assertTrue(page.contains("else if (action === 'skipQuestion') send({ type: 'skipQuestion', requestId: b.dataset.req });"))
        assertTrue(page.contains("if (!trusted.has(b)) return;"))
        assertTrue(page.contains("h('button', { 'data-action': 'skipQuestion', 'data-req': id }, 'Skip')"))
    }

    @Test fun toolCallsRenderAsCompactRows() {
        val js = WebAssets.page()
        assertTrue(js.contains("class: 'tool-row'"))
        assertTrue(js.contains("firstLine(str(i.description)).trim() || firstLine(str(i.command))"))
        assertTrue(js.contains("const expandedTools = new Set();"))
        assertTrue(js.contains("row.classList.contains('expandable') && !(window.getSelection && window.getSelection().toString())) toggleRow(row);"))
        assertTrue(js.contains("t.status !== 'awaiting_permission'"))
        assertTrue(js.contains("@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }"))
        val body = js.substringAfter("function renderToolRow(").substringBefore("function renderTool(")
        assertFalse(body.contains("innerHTML"))
        assertFalse(body.contains("marked"))
    }
}
