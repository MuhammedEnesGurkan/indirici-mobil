package com.movietrack.app.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object Format {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0 || v >= 100) String.format(Locale.US, "%.0f %s", v, u[i]) else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun speed(bps: Long): String = if (bps > 0) "${bytes(bps)}/s" else ""

    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private val ILLEGAL = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")

    fun safeName(s: String, max: Int = 120): String {
        var out = s.replace(ILLEGAL, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (out.length > max) out = out.take(max).trim()
        return out.ifEmpty { "dosya" }
    }

    fun extOf(name: String): String {
        val clean = name.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val dot = clean.lastIndexOf('.')
        return if (dot >= 0 && clean.length - dot in 2..6) clean.substring(dot + 1).lowercase(Locale.ROOT) else ""
    }

    fun fileNameFromUrl(url: String): String = try {
        URLDecoder.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/'), "UTF-8").ifEmpty { "dosya" }
    } catch (e: Exception) { "dosya" }

    fun host(url: String): String = try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
}

/** Basit anahtar=değer kodlayıcı (Room'da ek bilgi saklamak için). */
object KV {
    fun encode(map: Map<String, String>): String =
        map.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

    fun decode(s: String?): Map<String, String> {
        if (s.isNullOrEmpty()) return emptyMap()
        return s.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }
}

/** Dosya türüne göre kategori. */
object Category {
    const val VIDEO = "Video"
    const val MUSIC = "Müzik"
    const val DOC = "Belge"
    const val IMAGE = "Resim"
    const val ARCHIVE = "Arşiv"
    const val APP = "Uygulama"
    const val OTHER = "Diğer"
    val ALL = listOf(VIDEO, MUSIC, DOC, IMAGE, ARCHIVE, APP, OTHER)

    fun of(fileName: String, mime: String = ""): String {
        val e = Format.extOf(fileName)
        return when {
            mime.startsWith("video/") || e in setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "3gp", "mpg") -> VIDEO
            mime.startsWith("audio/") || e in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "wma") -> MUSIC
            mime.startsWith("image/") || e in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic") -> IMAGE
            e in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "odt", "csv", "srt", "vtt", "ass") -> DOC
            e in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> ARCHIVE
            e in setOf("apk", "xapk", "apks", "exe", "msi", "dmg") -> APP
            else -> OTHER
        }
    }
}

object Lang {
    private val map = mapOf(
        "tur" to "tr", "turkish" to "tr", "türkçe" to "tr", "eng" to "en", "english" to "en", "ingilizce" to "en",
        "ger" to "de", "deu" to "de", "german" to "de", "fre" to "fr", "fra" to "fr", "french" to "fr",
        "spa" to "es", "spanish" to "es", "ita" to "it", "italian" to "it", "por" to "pt", "portuguese" to "pt",
        "rus" to "ru", "russian" to "ru", "ara" to "ar", "arabic" to "ar", "jpn" to "ja", "japanese" to "ja",
        "kor" to "ko", "korean" to "ko", "chi" to "zh", "zho" to "zh", "chinese" to "zh", "hin" to "hi",
    )
    private val iso3 = mapOf("tr" to "tur", "en" to "eng", "de" to "ger", "fr" to "fre", "es" to "spa", "it" to "ita", "pt" to "por",
        "ru" to "rus", "ar" to "ara", "ja" to "jpn", "ko" to "kor", "zh" to "chi", "hi" to "hin")

    fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val l = s.trim().lowercase(Locale.ROOT)
        map[l]?.let { return it }
        val base = l.split('-', '_')[0]
        map[base]?.let { return it }
        if (base.length == 2 && base.all { it.isLetter() }) return base
        for (w in l.split(Regex("[^\\p{L}]+"))) map[w]?.let { return it }
        return ""
    }

    /** Altyazı dosya adından dil: "xYz-eng-film.vtt" -> en, "abc-pt-sd-123.vtt" -> pt, "tr.vtt" -> tr. */
    fun fromFileName(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
        val tokens = name.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        for (t in tokens) {
            map[t]?.let { return it }
            if (t.length == 2 && t in iso3) return t
        }
        return ""
    }

    private val names = mapOf("tr" to "Türkçe", "en" to "İngilizce", "de" to "Almanca", "fr" to "Fransızca", "es" to "İspanyolca", "it" to "İtalyanca",
        "pt" to "Portekizce", "ru" to "Rusça", "ar" to "Arapça", "ja" to "Japonca", "ko" to "Korece", "zh" to "Çince", "hi" to "Hintçe")
    fun displayName(code: String) = names[normalize(code)] ?: "Bilinmeyen dil"

    fun toIso3(code: String): String = iso3[normalize(code)] ?: "und"
    fun audLabel(code: String) = "aud:${normalize(code).ifEmpty { "und" }}"
    fun subLabel(code: String) = "subtitle:${normalize(code).ifEmpty { "und" }}"
}
