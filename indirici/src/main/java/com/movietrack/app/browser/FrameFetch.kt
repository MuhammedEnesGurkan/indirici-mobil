package com.movietrack.app.browser

import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.webkit.JavaScriptReplyProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Yedek indirme yolu: bazı sunucular uygulamanın doğrudan isteğini reddeder (HTTP 403). O zaman
 * dosya, medyanın bulunduğu oynatıcı çerçevesinin içinden (aynı kaynak, çerez ve başlıklarla) istenir.
 * İndirme sürerken o sekmenin açık kalması gerekir.
 */
object FrameFetch {
    private val proxies = ConcurrentHashMap<String, JavaScriptReplyProxy>() // kaynak (origin) -> kanal
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ByteArray?>>()
    private val ids = AtomicLong(1)
    private val main = Handler(Looper.getMainLooper())

    fun register(origin: String, proxy: JavaScriptReplyProxy) { proxies[origin.trimEnd('/')] = proxy }

    /** Uygulamaya gelen "fetchResult" mesajlarını yakalar. */
    fun onMessage(json: String): Boolean {
        if (!json.contains("\"fetchResult\"")) return false
        try {
            val o = JSONObject(json)
            val d = pending.remove(o.optLong("id")) ?: return true
            if (o.has("b64")) d.complete(Base64.decode(o.getString("b64"), Base64.DEFAULT)) else d.complete(null)
        } catch (_: Exception) {}
        return true
    }

    private fun originOf(url: String?): String? = try {
        val u = URI(url ?: return null); "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    } catch (_: Exception) { null }

    /** @param referer medyanın bulunduğu çerçevenin adresi */
    suspend fun fetch(url: String, referer: String?, range: LongArray? = null): ByteArray? {
        val proxy = originOf(referer)?.let { proxies[it] } ?: return null
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<ByteArray?>()
        pending[id] = d
        val msg = JSONObject().put("t", "fetch").put("id", id).put("url", url)
        if (range != null) msg.put("range", "bytes=${range[0]}-${if (range[1] >= 0) range[1] else ""}")
        main.post { try { proxy.postMessage(msg.toString()) } catch (_: Exception) { d.complete(null) } }
        return withTimeoutOrNull(90_000) { d.await() }.also { pending.remove(id) }
    }
}
