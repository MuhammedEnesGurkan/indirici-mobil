package com.movietrack.app.browser

import com.movietrack.app.util.Format
import java.util.Locale

data class DetectedMedia(
    val url: String,
    val kind: String, // hls | dash | video | audio | subtitle | file
    val referer: String,
    val title: String,
    val duration: Double = 0.0,
    val manifestText: String? = null,
    val lang: String = "",
    val isAd: Boolean = false,
    val detectedAt: Long = System.currentTimeMillis(),
)

object Detection {
    private val video = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "wmv", "3gp", "mpg")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private val segment = setOf("ts", "m4s", "cmfv", "cmfa", "m4f")
    private val segmentName = Regex("(?i)(seg|segment|chunk|frag|fragment)[-_]?\\d+")

    fun classify(url: String): String? {
        if (!url.startsWith("http")) return null
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?').substringBefore('#')
        val ext = Format.extOf(path)
        return when {
            ext == "m3u8" || path.contains(".m3u8/") -> "hls"
            ext == "mpd" -> "dash"
            ext in segment -> null
            ext in video -> if (segmentName.containsMatchIn(path)) null else "video"
            ext in audio -> if (segmentName.containsMatchIn(path)) null else "audio"
            ext == "vtt" || ext == "srt" -> if (segmentName.containsMatchIn(path) || Regex("[-_/]\\d+\\.vtt$").containsMatchIn(path)) null else "subtitle"
            else -> null
        }
    }

    // Reklam ve izleme sunucuları (engelleme + medya listesinde "AD" işareti)
    val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "imasdk.googleapis.com", "adservice.google.com", "fwmrm.net", "springserve.com", "spotxchange.com",
        "adsrvr.org", "moatads.com", "innovid.com", "teads.tv", "smartadserver.com", "adnxs.com", "pubmatic.com", "rubiconproject.com",
        "openx.net", "criteo.com", "criteo.net", "outbrain.com", "taboola.com", "adform.net", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "admatic.com.tr", "serving-sys.com",
        "flashtalking.com", "scorecardresearch.com", "hotjar.com", "facebook.net", "connect.facebook.net", "amazon-adsystem.com",
        "mgid.com", "onclickads.net", "adcash.com", "hilltopads.net", "a-ads.com", "yandex.ru/ads", "an.yandex.ru", "mc.yandex.ru",
        "clarity.ms", "quantserve.com", "zedo.com", "revcontent.com", "vidoomy.com", "aniview.com", "connatix.com", "primis.tech",
    )

    fun isAdHost(url: String): Boolean {
        val h = Format.host(url).lowercase(Locale.ROOT)
        if (h.isEmpty()) return false
        return AD_HOSTS.any { d -> !d.contains('/') && (h == d || h.endsWith(".$d")) }
    }
}
