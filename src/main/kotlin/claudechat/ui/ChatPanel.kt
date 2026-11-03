package claudechat.ui

import claudechat.session.SessionManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/** JCEF browser hosting the chat page, wired to a [ChatBridge]. */
class ChatPanel(private val project: Project, parent: Disposable) : Disposable {
    val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val bridge = ChatBridge(project, ::post)
    @Volatile private var pageUrl: String? = null
    @Volatile private var disposed = false

    init {
        Disposer.register(parent, this)

        query.addHandler { json ->
            ApplicationManager.getApplication().invokeLater({ bridge.handle(json) }, project.disposed)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                // The first main-frame load is our own page; anything else never gets the bridge.
                val url = cefBrowser.url
                val page = pageUrl ?: url.also { pageUrl = it }
                if (url != page) return
                val js = "window.__send = function(m) { ${query.inject("m")} }; window.dispatchEvent(new Event('bridge-ready'));"
                cefBrowser.executeJavaScript(js, cefBrowser.url, 0)
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            // Once the chat page has loaded, refuse every main-frame navigation (dropped files, stray links).
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean,
            ): Boolean = frame.isMain && pageUrl != null
        }, browser.cefBrowser)
        browser.loadHTML(WebAssets.page())
    }

    private fun post(json: String) {
        if (disposed) return
        browser.cefBrowser.executeJavaScript("window.chat && window.chat.receive($json);", "", 0)
    }

    override fun dispose() {
        disposed = true
        SessionManager.getInstance(project).detach(bridge)
        Disposer.dispose(query)
        Disposer.dispose(browser)
    }
}
