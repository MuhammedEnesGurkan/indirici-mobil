package com.movietrack.app.download

import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.util.Category
import com.movietrack.app.util.EpisodeParser
import com.movietrack.app.util.Format
import com.movietrack.app.util.Lang

/** İndirmeden önce bir bağlantıyı analiz eder: kaliteler, ses dilleri, altyazılar, süre, boyut. */
object Analyzer {
    data class Quality(val id: String, val label: String, val height: Int, val bandwidth: Long, val estSize: Long)
    data class Track(val id: String, val lang: String, val name: String, val label: String, val isDefault: Boolean = false)
    data class Info(
        val kind: String, // hls | dash | direct
        val url: String,
        val fileName: String,
        val mime: String = "",
        val size: Long = 0,
        val duration: Double = 0.0,
        val qualities: List<Quality> = emptyList(),
        val audios: List<Track> = emptyList(),
        val subtitles: List<Track> = emptyList(),
        val adBreaks: Int = 0,
        val drm: Boolean = false,
        val live: Boolean = false,
        val encrypted: Boolean = false,
        val muxedAudioLang: String = "",
    )

    private fun req(url: String, referer: String?, ua: String?) = DownloadEntity(url = url, type = "direct", fileName = "", referer = referer, userAgent = ua)

    suspend fun analyze(url: String, kind: String?, referer: String?, ua: String?, manifestText: String? = null): Info {
        val k = kind ?: com.movietrack.app.browser.Detection.classify(url) ?: "direct"
        return when (k) {
            "hls" -> hls(url, referer, ua, manifestText)
            "dash" -> dash(url, referer, ua, manifestText)
            else -> direct(url, referer, ua)
        }
    }

    private suspend fun hls(url: String, referer: String?, ua: String?, text0: String?): Info {
        val e = req(url, referer, ua)
        val text = text0?.takeIf { it.trimStart().startsWith("#EXTM3U") } ?: Http.text(e, url)
        if (!text.trimStart().startsWith("#EXTM3U")) error("Geçerli bir m3u8 değil")
        if (!Hls.isMaster(text)) {
            val m = Hls.parseMedia(text, url)
            val h = Regex("(2160|1440|1080|720|576|480|360|240)p?").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return Info("hls", url, "", duration = m.duration, qualities = listOf(Quality(url, if (h > 0) "${h}p" else "Varsayılan", h, 0, 0)),
                adBreaks = m.segments.count { it.isAd && !it.discontinuity }.coerceAtMost(1) + m.segments.windowed(2).count { (a, b) -> !a.isAd && b.isAd },
                drm = m.drm, live = m.live, encrypted = m.segments.any { it.key != null })
        }
        val master = Hls.parseMaster(text, url)
        var duration = 0.0
        var ads = 0
        var live = false
        var drm = master.drm
        var enc = false
        master.variants.firstOrNull()?.let { v ->
            runCatching {
                val m = Hls.parseMedia(Http.text(e, v.url), v.url)
                duration = m.duration
                ads = m.segments.windowed(2).count { (a, b) -> !a.isAd && b.isAd } + if (m.segments.firstOrNull()?.isAd == true) 1 else 0
                live = m.live; drm = drm || m.drm; enc = m.segments.any { it.key != null }
            }
        }
        val audioMedia = master.media.filter { it.type == "audio" }
        val audios = audioMedia.filter { it.url.isNotEmpty() }.distinctBy { it.url }.mapIndexed { i, r ->
            Track(r.url, r.lang, r.name, Lang.audLabel(r.lang), r.isDefault)
        }
        val subs = master.media.filter { it.type == "subtitles" && it.url.isNotEmpty() }.distinctBy { it.url }.map { r ->
            Track(r.url, r.lang, r.name, Lang.subLabel(r.lang))
        }
        val content = duration
        val qualities = master.variants.map { v ->
            Quality(v.url, listOfNotNull(if (v.height > 0) "${v.height}p" else null, if (v.bandwidth > 0) "${v.bandwidth / 100_000 / 10.0} Mbps" else null).joinToString(" · ").ifEmpty { "Varsayılan" },
                v.height, v.bandwidth, if (v.bandwidth > 0 && content > 0) (v.bandwidth / 8 * content).toLong() else 0)
        }
        return Info("hls", url, "", duration = duration, qualities = qualities, audios = audios, subtitles = subs, adBreaks = ads, drm = drm, live = live,
            encrypted = enc, muxedAudioLang = audioMedia.firstOrNull { it.url.isEmpty() }?.lang ?: "")
    }

    private suspend fun dash(url: String, referer: String?, ua: String?, text0: String?): Info {
        val text = text0?.takeIf { it.contains("<MPD") } ?: Http.text(req(url, referer, ua), url)
        val mpd = Dash.parse(text, url)
        val vids = mpd.reps.filter { it.kind == "video" }
        val auds = mpd.reps.filter { it.kind == "audio" }.groupBy { it.lang }.map { (_, l) -> l.maxByOrNull { it.bandwidth }!! }
        return Info("dash", url, "", duration = mpd.duration, drm = mpd.drm,
            qualities = vids.map { Quality(it.id, "${it.height}p · ${it.bandwidth / 100_000 / 10.0} Mbps", it.height, it.bandwidth, (it.bandwidth / 8 * mpd.duration).toLong()) },
            audios = auds.map { Track(it.id, it.lang, it.label, Lang.audLabel(it.lang)) },
            subtitles = mpd.reps.filter { it.kind == "text" && it.segments.size == 1 }.map { Track(it.segments[0].url, it.lang, it.label, Lang.subLabel(it.lang)) })
    }

    private suspend fun direct(url: String, referer: String?, ua: String?): Info {
        var size = 0L
        var mime = ""
        var name = Format.fileNameFromUrl(url)
        runCatching {
            Http.execute(Http.request(req(url, referer, ua), url, longArrayOf(0, 0))).use { res ->
                val cr = res.header("Content-Range")
                size = cr?.substringAfter('/')?.toLongOrNull() ?: res.body?.contentLength()?.takeIf { it > 0 } ?: 0
                mime = res.header("Content-Type")?.substringBefore(';')?.trim() ?: ""
                res.header("Content-Disposition")?.let { cd ->
                    Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(cd)?.groupValues?.get(1)?.let { name = java.net.URLDecoder.decode(it, "UTF-8") }
                }
            }
        }
        if (Format.extOf(name).isEmpty()) {
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) name = "$name.$ext"
        }
        return Info("direct", url, Format.safeName(name), mime, size)
    }

    /** Sayfa başlığından dosya adı ve klasör (diziler: Diziler/Dizi/Sezon N). */
    fun naming(pageTitle: String?, fallback: String): Pair<String, String> {
        val p = EpisodeParser.parse(pageTitle?.takeIf { it.isNotBlank() } ?: fallback)
        return if (p.isEpisode && p.series.isNotBlank()) {
            val s = p.season ?: 1
            Format.safeName("${p.series} - S${s.toString().padStart(2, '0')}E${(p.episode ?: 0).toString().padStart(2, '0')}") to "Diziler/${Format.safeName(p.series)}/Sezon $s"
        } else Format.safeName(p.title.ifBlank { fallback }) to "Filmler"
    }

    fun categoryFolder(fileName: String, mime: String) = Category.of(fileName, mime)
}
