package com.movietrack.app.download

/** İndirme penceresindeki seçimlerden indirme isteği (pencere ve testler aynı kodu kullanır). */
object Requests {
    fun build(
        i: Analyzer.Info, url: String, name: String, folder: String,
        quality: String?, audioIds: Collection<String>, subIds: Collection<String>,
        mode: String = "mp4", skipAds: Boolean = true,
        referer: String? = null, userAgent: String? = null, pageTitle: String? = null,
        inline: Map<String, String> = emptyMap(),
        audioOnly: Boolean = false,
        alsoAudio: Boolean = false,
    ): NewDownload {
        val labels = buildList {
            i.qualities.firstOrNull { it.id == quality }?.let { if (it.height > 0) add("${it.height}p") }
            i.audios.filter { it.id in audioIds }.forEach { add(it.label) }
            i.subtitles.filter { it.id in subIds }.forEach { add(it.label) }
        }.joinToString(" ")
        if (i.kind == "direct") {
            return NewDownload(url = url, fileName = name, mime = i.mime, size = i.size, referer = referer, userAgent = userAgent, pageUrl = referer, title = pageTitle, labels = labels,
                meta = mapOf("folder" to folder))
        }
        val ua = i.userAgent ?: userAgent
        val meta = mutableMapOf(
            "variant" to (quality ?: url), "mode" to mode, "skipAds" to if (skipAds) "1" else "0", "base" to name, "folder" to folder,
            "audios" to i.audios.filter { it.id in audioIds }.joinToString("\n") { "${it.id}|${it.lang}|${it.name}" },
            "subs" to i.subtitles.filter { it.id in subIds }.joinToString("\n") { "${it.id}|${it.lang}|${it.name}" },
            "muxedLang" to i.muxedAudioLang,
        )
        inline.forEach { (k, v) -> if (v.length < 300_000) meta["inline:$k"] = v }
        if (audioOnly) meta["audioOnly"] = "1"
        if (alsoAudio) meta["alsoAudio"] = "1"
        if (audioOnly) return NewDownload(url = url, fileName = "$name.m4a", type = i.kind, mime = "audio/mp4", size = 0,
            referer = referer ?: if (i.kind == "youtube") i.url else null, userAgent = ua, pageUrl = referer ?: i.url, title = pageTitle, meta = meta, labels = labels)
        return NewDownload(url = url, fileName = "$name.mp4", type = i.kind, mime = "video/mp4", size = i.qualities.firstOrNull { it.id == quality }?.estSize ?: 0,
            referer = referer ?: if (i.kind == "youtube") i.url else null, userAgent = ua, pageUrl = referer ?: i.url, title = pageTitle, meta = meta, labels = labels)
    }
}
