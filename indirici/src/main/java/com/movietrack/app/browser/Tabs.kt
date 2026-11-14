package com.movietrack.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.movietrack.app.App
import com.movietrack.app.data.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

sealed class BrowserEvent {
    data class FileDownload(val url: String, val userAgent: String, val contentDisposition: String?, val mime: String?, val length: Long, val referer: String) : BrowserEvent()
    data class LongPress(val tab: Tab, val type: Int, val extra: String) : BrowserEvent()
    data class Fullscreen(val view: View?, val callback: WebChromeClient.CustomViewCallback?) : BrowserEvent()
}

/** Sayfa kimliği: adres parçası (#...) aynı sayfa sayılır. */
internal fun pageKey(url: String) = url.substringBefore('#')

class Tab(val id: Long, val incognito: Boolean, context: Context) {
    val title = MutableStateFlow(if (incognito) "Gizli sekme" else "Yeni sekme")
    val url = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val media = MutableStateFlow<List<DetectedMedia>>(emptyList())
    val isHome = MutableStateFlow(true)
    @Volatile var currentUrl = ""
    @Volatile var playerDuration = 0.0
    /** Bir önceki sayfanın adresi: geçişten sonra eski sayfadan gecikmeli gelen bildirimler listeye eklenmez. */
    @Volatile var previousUrl = ""
    val webView: WebView = WebView(context)

    /**
     * Sayfa değişti (tam yükleme ya da SPA adres değişimi): önceki bölümün bağlantıları listede kalmasın.
     * [keepRecentMs] içinde bulunanlar korunur; SPA'larda yeni bölümün akışı adres değişmeden hemen önce yüklenebilir.
     */
    fun resetMedia(newUrl: String, keepRecentMs: Long = 0) {
        if (currentUrl.isNotEmpty() && pageKey(currentUrl) != pageKey(newUrl)) previousUrl = currentUrl
        playerDuration = 0.0
        if (keepRecentMs <= 0) media.value = emptyList()
        else {
            val since = System.currentTimeMillis() - keepRecentMs
            media.update { list -> list.filter { it.detectedAt >= since } }
        }
    }

    fun addMedia(m: DetectedMedia) {
        // Önceki sayfadan geç gelen istek/bildirim (ör. eski oynatıcının son isteği)
        if (previousUrl.isNotEmpty() && m.referer == previousUrl && pageKey(previousUrl) != pageKey(currentUrl)) return
        media.update { list ->
            if (list.any { it.url == m.url }) {
                list.map { if (it.url == m.url) it.copy(duration = maxOf(it.duration, m.duration), manifestText = it.manifestText ?: m.manifestText) else it }
            } else list + m.copy(isAd = m.isAd || (m.kind != "hls" && m.kind != "dash" && Detection.isLikelyAd(m.url)) || Detection.isAdHost(m.url))
        }
    }

    fun load(input: String) {
        val s = input.trim()
        val url = when {
            s.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) -> s
            s.contains('.') && !s.contains(' ') -> "https://$s"
            else -> App.instance.settings.searchEngine + Uri.encode(s)
        }
        isHome.value = false
        webView.loadUrl(url)
    }
}

@SuppressLint("SetJavaScriptEnabled")
object TabManager {
    val tabs = MutableStateFlow<List<Tab>>(emptyList())
    val current = MutableStateFlow<Tab?>(null)
    val events = MutableSharedFlow<BrowserEvent>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nextId = 1L
    private var wrapper: MutableContextWrapper? = null
    private val hookJs by lazy { App.instance.assets.open("hook.js").bufferedReader().use { it.readText() } }

    /** WebView'lar etkinliğe bağlı bağlamla oluşturulur; etkinlik yeniden oluşursa bağlam güncellenir. */
    fun attach(ctx: Context) {
        val w = wrapper
        if (w == null) wrapper = MutableContextWrapper(ctx) else w.baseContext = ctx
        if (tabs.value.isEmpty()) newTab(null)
    }

    fun select(tab: Tab) { current.value = tab }

    fun close(tab: Tab) {
        tabs.update { it - tab }
        if (current.value == tab) current.value = tabs.value.lastOrNull()
        tab.webView.stopLoading()
        if (tab.incognito) tab.webView.clearCache(true)
        tab.webView.destroy()
        if (tabs.value.isEmpty()) newTab(null)
    }

    fun newTab(url: String?, incognito: Boolean = false): Tab {
        val tab = Tab(nextId++, incognito, wrapper ?: App.instance)
        configure(tab)
        tabs.update { it + tab }
        current.value = tab
        if (url != null) tab.load(url)
        return tab
    }

    fun applySettings() {
        for (t in tabs.value) applyUserAgent(t.webView)
    }

    private fun applyUserAgent(w: WebView) {
        val s = App.instance.settings
        w.settings.userAgentString = if (s.desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36" else null
        w.settings.javaScriptCanOpenWindowsAutomatically = !s.popupBlock
    }

    private fun onBridgeMessage(tab: Tab, json: String) {
        try {
            val o = JSONObject(json)
            val ref = o.optString("ref").takeIf { it.startsWith("http") } ?: tab.currentUrl
            val title = tab.title.value
            when (o.optString("t")) {
                "manifest" -> {
                    val u = o.optString("url")
                    if (u.startsWith("http")) tab.addMedia(DetectedMedia(u, o.optString("kind", "hls"), ref, title, manifestText = o.optString("text")))
                }
                "urls" -> {
                    val arr = o.optJSONArray("urls") ?: return
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i)
                        val k = Detection.classify(u)
                        if (k == "hls" || k == "dash") tab.addMedia(DetectedMedia(u, k, ref, title))
                    }
                }
                "media" -> {
                    val u = o.optString("url")
                    val k = o.optString("kind")
                    val kind = if (k == "subtitle") "subtitle" else Detection.classify(u) ?: k
                    tab.addMedia(DetectedMedia(u, kind, ref, title, o.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it }, lang = o.optString("lang")))
                }
                "player" -> {
                    tab.playerDuration = maxOf(tab.playerDuration, o.optDouble("duration", 0.0))
                    tab.media.update { list -> list.map { if ((it.kind == "hls" || it.kind == "dash") && it.duration == 0.0) it.copy(duration = tab.playerDuration) else it } }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun configure(tab: Tab) {
        val w = tab.webView
        val app = App.instance
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (tab.incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        applyUserAgent(w)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        // Kanca: tüm çerçevelerde belge başında; yoksa sayfa başladığında
        // Kanca köprüsü: tercihen WebMessageListener (tüm çerçeveler), yoksa JavascriptInterface
        val listenerOk = try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(w, "MTBridge", setOf("*")) { _, message, origin, _, reply ->
                    FrameFetch.register(origin.toString(), reply)
                    message.data?.let { if (!FrameFetch.onMessage(it)) onBridgeMessage(tab, it) }
                }
                true
            } else false
        } catch (_: Throwable) { false }
        if (!listenerOk) {
            w.addJavascriptInterface(object {
                @JavascriptInterface fun postMessage(s: String) = onBridgeMessage(tab, s)
            }, "MTBridge")
        }
        val docStart = try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, hookJs, setOf("*")); true
            } else false
        } catch (_: Throwable) { false }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (app.settings.adBlock && !request.isForMainFrame && Detection.isAdHost(url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                Detection.classify(url)?.let { kind ->
                    val ref = request.requestHeaders["Referer"] ?: tab.currentUrl
                    tab.addMedia(DetectedMedia(url, kind, ref, tab.title.value, if (kind == "hls" || kind == "dash") tab.playerDuration else 0.0))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "about" || u.scheme == "data" || u.scheme == "blob") return false
                // intent://, market://, tel: vb. dış uygulamalar
                try {
                    val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    view.context.startActivity(i)
                } catch (_: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.resetMedia(url)
                tab.currentUrl = url
                tab.url.value = url
                tab.isHome.value = false
                if (!docStart) view.evaluateJavascript(hookJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                // WebView yalnızca ana iş parçacığından okunabilir: başlığı burada al
                val pageTitle = view.title ?: url
                tab.title.value = pageTitle
                if (!tab.incognito && url.startsWith("http")) {
                    scope.launch { app.db.browser().addHistory(HistoryEntity(title = pageTitle, url = url)) }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // SPA geçişi (pushState/replaceState, sayfa yeniden yüklenmeden sonraki bölüme geçiş): eski bağlantıları temizle
                if (!isReload && tab.currentUrl.isNotEmpty() && pageKey(url) != pageKey(tab.currentUrl)) tab.resetMedia(url, keepRecentMs = 1500)
                tab.currentUrl = url
                tab.url.value = url
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress.value = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { if (!title.isNullOrBlank()) tab.title.value = title }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // Pop-up engelleme: kullanıcı dokunmadan açılan pencereler engellenir
                if (app.settings.popupBlock && !isUserGesture) return false
                val t = newTab(null, tab.incognito)
                t.isHome.value = false
                (resultMsg.obj as WebView.WebViewTransport).webView = t.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) { events.tryEmit(BrowserEvent.Fullscreen(view, callback)) }
            override fun onHideCustomView() { events.tryEmit(BrowserEvent.Fullscreen(null, null)) }
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            events.tryEmit(BrowserEvent.FileDownload(url, userAgent, contentDisposition, mimetype, contentLength, tab.currentUrl))
        }
        w.setOnLongClickListener {
            val r = w.hitTestResult
            val extra = r.extra
            if (extra != null && r.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                events.tryEmit(BrowserEvent.LongPress(tab, r.type, extra))
                true
            } else false
        }
    }

    /** Tarama verilerini temizle (önbellek, çerezler, geçmiş). */
    fun clearData() {
        for (t in tabs.value) t.webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        scope.launch { App.instance.db.browser().clearHistory() }
    }
}
