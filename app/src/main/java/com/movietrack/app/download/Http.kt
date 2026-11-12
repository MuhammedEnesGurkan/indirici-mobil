package com.movietrack.app.download

import android.webkit.CookieManager
import com.movietrack.app.data.DownloadEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class HttpException(val code: Int, url: String) : IOException("HTTP $code")

object Http {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun request(e: DownloadEntity?, url: String, range: LongArray? = null): Request {
        val b = Request.Builder().url(url)
        b.header("User-Agent", e?.userAgent ?: DEFAULT_UA)
        e?.referer?.let { ref ->
            b.header("Referer", ref)
            // Oynatıcı sunucuları çoğu zaman çağıran çerçevenin kaynağını (Origin) da bekler
            try {
                val r = java.net.URI(ref); val u = java.net.URI(url)
                if (r.host != null && r.host != u.host) b.header("Origin", "${r.scheme}://${r.host}${if (r.port > 0) ":${r.port}" else ""}")
            } catch (_: Exception) {}
        }
        // Tarayıcıdaki oturum çerezleri (her istek anında güncel)
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null } ?: e?.cookies
        if (!cookie.isNullOrEmpty()) b.header("Cookie", cookie)
        if (range != null) b.header("Range", "bytes=${range[0]}-${if (range[1] >= 0) range[1] else ""}")
        return b.build()
    }

    fun execute(req: Request): Response {
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) { val c = res.code; res.close(); throw HttpException(c, req.url.toString()) }
        return res
    }

    /** Yeniden deneyerek çalıştırır. 4xx (403/408/429 hariç) kalıcı sayılır. */
    suspend fun <T> retry(times: Int = 5, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                return block()
            } catch (e: HttpException) {
                if ((e.code in 400..499 && e.code !in setOf(408, 429)) || attempt >= times) throw e  // 403: çerçeve yedeğine hemen geç
            } catch (e: IOException) {
                if (attempt >= times) throw e
            }
            attempt++
            delay(minOf(16_000L, 800L shl (attempt - 1)))
        }
    }

    private val textClient: OkHttpClient by lazy { client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build() }

    /** Sunucu doğrudan isteği reddederse (401/403) oynatıcı çerçevesinin içinden dener. */
    private suspend fun <T> withFrameFallback(e: DownloadEntity?, url: String, range: LongArray?, map: (ByteArray) -> T, block: suspend () -> T): T =
        try { block() } catch (x: HttpException) {
            if (x.code != 403 && x.code != 401) throw x
            val data = com.movietrack.app.browser.FrameFetch.fetch(url, e?.referer, range) ?: throw x
            map(data)
        }

    suspend fun text(e: DownloadEntity?, url: String): String = withFrameFallback(e, url, null, { String(it) }) { textDirect(e, url) }

    private suspend fun textDirect(e: DownloadEntity?, url: String): String = retry(2) {
        val req = request(e, url)
        val res = try { textClient.newCall(req).execute() } catch (t: java.io.InterruptedIOException) {
            throw java.net.SocketTimeoutException("Sunucu yanıt vermedi (zaman aşımı)")
        }
        if (!res.isSuccessful) { val c = res.code; res.close(); throw HttpException(c, url) }
        res.use { it.body!!.string() }
    }

    suspend fun bytes(e: DownloadEntity?, url: String, range: LongArray? = null, onBytes: (Int) -> Unit = {}): ByteArray =
        withFrameFallback(e, url, range, { it.also { d -> onBytes(d.size) } }) { bytesDirect(e, url, range, onBytes) }

    private suspend fun bytesDirect(e: DownloadEntity?, url: String, range: LongArray?, onBytes: (Int) -> Unit): ByteArray = retry {
        execute(request(e, url, range)).use { res ->
            val out = java.io.ByteArrayOutputStream(maxOf(16 * 1024, res.body!!.contentLength().toInt().coerceAtLeast(0)))
            val buf = ByteArray(64 * 1024)
            res.body!!.byteStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
            out.toByteArray()
        }
    }
}
