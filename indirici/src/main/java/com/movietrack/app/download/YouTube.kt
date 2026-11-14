package com.movietrack.app.download

import com.movietrack.app.util.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube doğrudan indirme: InnerTube "player" servisinden (yt-dlp'nin ve eklentinin kullandığı istemciler)
 * imza çözümü gerektirmeyen doğrudan görüntü/ses adreslerini alır. Yalnızca MP4'e yazılabilen biçimler
 * (H.264 görüntü + AAC ses) listelenir. Görüntü ve ses ayrı iner, sonra tek MP4'te birleştirilir.
 */
object YouTube {
    private val HOSTS = Regex("(^|\\.)(youtube\\.com|youtube-nocookie\\.com|youtu\\.be)$", RegexOption.IGNORE_CASE)

    fun id(url: String): String? = try {
        val u = java.net.URI(url)
        val host = u.host ?: ""
        if (!HOSTS.containsMatchIn(host)) null
        else if (host.endsWith("youtu.be")) u.path.trim('/').take(11).takeIf { it.length == 11 }
        else Regex("(?:^|&)v=([\\w-]{11})").find(u.rawQuery ?: "")?.groupValues?.get(1)
            ?: Regex("^/(?:shorts|embed|live|v)/([\\w-]{11})").find(u.path ?: "")?.groupValues?.get(1)
    } catch (_: Exception) { null }

    private class Client(val key: String, val id: Int, val ua: String, val client: JSONObject)

    // Sürümler YouTube tarafında değiştikçe güncellenmesi gerekebilir (eklentiyle aynı profiller)
    private val clients get() = listOf(
        Client("visionos", 101, "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02").put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
                .put("osName", "visionOS").put("osVersion", "26.5.23O471").put("hl", "tr").put("gl", "TR")),
        Client("ios", 5, "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            JSONObject().put("clientName", "IOS").put("clientVersion", "21.26.4").put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone").put("osVersion", "18.3.2.22D82").put("hl", "tr").put("gl", "TR")),
    )

    private suspend fun player(c: Client, videoId: String, visitor: String?): JSONObject = withContext(Dispatchers.IO) {
        val ctx = JSONObject(c.client.toString()).put("userAgent", c.ua)
        if (visitor != null) ctx.put("visitorData", visitor)
        val body = JSONObject()
            .put("context", JSONObject().put("client", ctx))
            .put("videoId", videoId)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            .put("contentCheckOk", true).put("racyCheckOk", true)
        val req = Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.ua).header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.client.getString("clientVersion")).header("Origin", "https://www.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Http.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw HttpException(res.code, req.url.toString())
            JSONObject(res.body!!.string())
        }
    }

    private fun text(o: JSONObject?): String = when {
        o == null -> ""
        o.has("simpleText") -> o.optString("simpleText")
        else -> o.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } } ?: ""
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** İçerik uzunluğu: adresteki clen parametresi ya da yanıttaki contentLength. */
    fun size(url: String): Long = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    suspend fun analyze(url: String): Analyzer.Info {
        val vid = id(url) ?: userError("Bu bir YouTube video bağlantısı değil.")
        val errors = ArrayList<String>()
        var visitor: String? = null
        for (c in clients) {
            try {
                var pr = player(c, vid, visitor)
                // Ziyaretçi kimliği olmayan istek "bot" doğrulamasına takılabilir; kimliği aynı yanıt verir
                if (pr.optJSONObject("playabilityStatus")?.optString("status") == "LOGIN_REQUIRED" && visitor == null) {
                    pr.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }?.let { visitor = it; pr = player(c, vid, it) }
                }
                val ps = pr.optJSONObject("playabilityStatus")
                val st = ps?.optString("status") ?: ""
                if (st.isNotEmpty() && st != "OK") {
                    val reason = ps?.optString("reason")?.takeIf { it.isNotEmpty() }
                        ?: text(ps?.optJSONObject("errorScreen")?.optJSONObject("playerErrorMessageRenderer")?.optJSONObject("reason")).ifEmpty { st }
                    errors += "${c.key}: $reason"; continue
                }
                val info = parse(pr, url, c.ua)
                if (info.drm) return info
                if (info.qualities.isEmpty()) { errors += "${c.key}: MP4 biçimi yok"; continue }
                // Adres gerçekten tümüyle iniyor mu? Belirteç isteyen adresler ilk ~60 sn'yi verip sonra 403 döner
                val probe = info.audios.firstOrNull()?.id ?: info.qualities.first().id
                val end = (size(probe) - 1024).coerceAtLeast(0)
                try {
                    withContext(Dispatchers.IO) {
                        Http.client.newCall(Request.Builder().url(probe).header("User-Agent", c.ua).header("Range", "bytes=$end-${end + 1023}").build()).execute().use {
                            if (!it.isSuccessful) throw HttpException(it.code, probe)
                        }
                    }
                } catch (t: Throwable) { errors += "${c.key}: ${t.message}"; continue }
                return info
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                errors += "${c.key}: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        throw UserError(
            if (errors.any { it.contains("oturum", true) || it.contains("bot", true) || it.contains("sign in", true) })
                "YouTube bu bağlantıdan indirmeye izin vermedi (oturum/bot doğrulaması istiyor). Biraz sonra ya da başka bir ağla deneyin."
            else "YouTube bu video için indirme adresi vermedi.",
            Exception(errors.joinToString("; ")),
        )
    }

    fun parse(pr: JSONObject, pageUrl: String, ua: String): Analyzer.Info {
        val sd = pr.optJSONObject("streamingData") ?: JSONObject()
        val vd = pr.optJSONObject("videoDetails") ?: JSONObject()
        val all = sd.optJSONArray("adaptiveFormats").objects()
        val drm = sd.has("licenseInfos") || sd.has("drmParams") || all.any { it.has("drmFamilies") }
        val fmts = all.filter { it.has("url") && !it.has("drmFamilies") }
        val dur = vd.optString("lengthSeconds").toDoubleOrNull() ?: 0.0
        // Ses: MP4 (AAC); dil başına en yüksek bit hızı, varsayılan önce
        val auds = fmts.filter { it.optString("mimeType").startsWith("audio/mp4") }
        val capTracks = pr.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks").objects()
        val asrLang = Lang.normalize(capTracks.firstOrNull { it.optString("kind") == "asr" }?.optString("languageCode"))
        val byLang = LinkedHashMap<String, JSONObject>()
        for (f in auds) {
            val at = f.optJSONObject("audioTrack")
            val lang = Lang.normalize(at?.optString("id")?.substringBefore('.')).ifEmpty { if (auds.none { it.has("audioTrack") }) asrLang else "" }
            val key = "$lang|${at?.optString("displayName") ?: ""}"
            val cur = byLang[key]
            if (cur == null || cur.optLong("bitrate") < f.optLong("bitrate")) byLang[key] = f
        }
        val audios = byLang.entries.sortedByDescending { it.value.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") == true }.map { (k, f) ->
            val lang = k.substringBefore('|')
            val name = f.optJSONObject("audioTrack")?.optString("displayName")?.takeIf { it.isNotEmpty() } ?: "Ses ${f.optLong("bitrate") / 1000} kbit/sn"
            Analyzer.Track(f.getString("url"), lang, name, Lang.audLabel(lang), isDefault = f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault") == true)
        }
        val audioSize = byLang.values.firstOrNull()?.let { it.optString("contentLength").toLongOrNull() ?: size(it.getString("url")) } ?: 0L
        // Görüntü: H.264 (MP4); çözünürlük ve kare hızı başına en yüksek bit hızı
        val vids = fmts.filter { it.optString("mimeType").startsWith("video/mp4") && it.optString("mimeType").contains("avc1") }
        val byRes = LinkedHashMap<String, JSONObject>()
        for (f in vids) {
            val h = minOf(f.optInt("height"), f.optInt("width").takeIf { it > 0 } ?: Int.MAX_VALUE)
            val key = "$h|${if (f.optInt("fps") > 30) 60 else 30}"
            val cur = byRes[key]
            if (cur == null || cur.optLong("bitrate") < f.optLong("bitrate")) byRes[key] = f
        }
        val qualities = byRes.values.sortedWith(compareByDescending<JSONObject> { minOf(it.optInt("height"), it.optInt("width")) }.thenByDescending { it.optInt("fps") }).map { f ->
            val h = minOf(f.optInt("height"), f.optInt("width"))
            val sz = (f.optString("contentLength").toLongOrNull() ?: size(f.getString("url"))) + audioSize
            Analyzer.Quality(f.getString("url"), "${h}p${if (f.optInt("fps") > 30) " ${f.optInt("fps")} fps" else ""} · H.264 MP4", h, f.optLong("bitrate"), sz, audio = false)
        }
        val subtitles = capTracks.map { c ->
            val lang = Lang.normalize(c.optString("languageCode"))
            val u = c.optString("baseUrl").let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
            val vtt = if (u.contains("fmt=")) u.replace(Regex("fmt=[^&]*"), "fmt=vtt") else "$u&fmt=vtt"
            Analyzer.Track(vtt, lang, text(c.optJSONObject("name")) + if (c.optString("kind") == "asr") " (otomatik)" else "", Lang.subLabel(lang))
        }
        val title = vd.optString("title").ifBlank { "YouTube ${vd.optString("videoId")}" }
        return Analyzer.Info("youtube", pageUrl, title, mime = "video/mp4", duration = dur, qualities = qualities, audios = audios, subtitles = subtitles,
            drm = drm, live = vd.optBoolean("isLive"), userAgent = ua)
    }
}
