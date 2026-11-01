package com.movietrack.app.download

import com.movietrack.app.util.Lang
import java.net.URI

/** HLS (m3u8) ayrıştırıcı: ana liste (kalite, ses, altyazı) ve medya listesi (segmentler, anahtar, reklam). */
object Hls {
    data class Variant(val url: String, val bandwidth: Long, val width: Int, val height: Int, val codecs: String, val audioGroup: String, val subGroup: String)
    data class Rendition(val type: String, val groupId: String, val lang: String, val name: String, val url: String, val isDefault: Boolean)
    data class Master(val variants: List<Variant>, val media: List<Rendition>, val drm: Boolean)
    data class Key(val method: String, val url: String, val iv: String?)
    data class Segment(
        val url: String, val duration: Double, val range: LongArray?, val key: Key?, val map: String?, val mapRange: LongArray?,
        val discontinuity: Boolean, val seq: Long, var isAd: Boolean = false,
    )
    data class Media(val segments: List<Segment>, val duration: Double, val live: Boolean, val drm: Boolean, val targetDuration: Double)

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    /**
     * Bazı siteler segmentleri .png/.jpg gibi gösterip başına sahte resim başlığı ekler; oynatıcıları bu
     * kısmı atar. Gerçek akışın başladığı yeri bulup öncesini atar (TS: arka arkaya 0x47 senkron baytları,
     * fMP4: ftyp/styp/moof/moov/sidx kutusu). Bulunamazsa veri olduğu gibi döner.
     */
    fun stripJunk(data: ByteArray): ByteArray {
        if (data.size < 188 * 3) return data
        fun ts(i: Int) = data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte() && data[i + 376] == 0x47.toByte()
        if (ts(0)) return data
        fun box(i: Int): Boolean {
            if (i + 8 > data.size) return false
            val t = String(data, i + 4, 4, Charsets.ISO_8859_1)
            return t in setOf("ftyp", "styp", "moof", "moov", "sidx", "emsg")
        }
        if (box(0)) return data
        val limit = minOf(data.size - 377, 256 * 1024)
        for (i in 1 until limit) {
            if (data[i] == 0x47.toByte() && ts(i)) return data.copyOfRange(i, data.size)
            if (box(i) && i >= 4) {
                // kutu boyutu makul mü (4 bayt büyük endian)
                val size = ((data[i].toLong() and 0xff) shl 24) or ((data[i + 1].toLong() and 0xff) shl 16) or ((data[i + 2].toLong() and 0xff) shl 8) or (data[i + 3].toLong() and 0xff)
                if (size >= 8 && size <= data.size - i) return data.copyOfRange(i, data.size)
            }
        }
        return data
    }

    /** Liste satırındaki codecs bilgisinden ses var mı: true/false, bilinmiyorsa null. */
    fun variantHasAudio(v: Variant): Boolean? {
        if (v.audioGroup.isNotEmpty()) return true
        if (v.codecs.isBlank()) return null
        return Regex("(?i)mp4a|ac-3|ec-3|opus|mp3|flac|vorbis|alac").containsMatchIn(v.codecs)
    }

    /**
     * İlk segmentten (TS ya da fMP4 başlangıç kutusu) ses izi olup olmadığını çıkarır.
     * true: ses var, false: yalnızca görüntü, null: anlaşılamadı.
     */
    fun segmentHasAudio(raw: ByteArray): Boolean? {
        val d = stripJunk(raw)
        if (d.size >= 3 && d[0] == 'I'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == '3'.code.toByte()) return true // paketli ses (AAC/MP3)
        if (d.size >= 2 && d[0] == 0xFF.toByte() && (d[1].toInt() and 0xF0) == 0xF0) return true // ADTS
        if (d.size >= 188 && d[0] == 0x47.toByte()) {
            val pmtPids = HashSet<Int>()
            var sawPmt = false
            var audio = false
            var i = 0
            while (i + 188 <= d.size) {
                if (d[i] != 0x47.toByte()) { i++; continue }
                val pusi = (d[i + 1].toInt() and 0x40) != 0
                val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
                val afc = (d[i + 3].toInt() shr 4) and 3
                var p = i + 4
                if (afc == 2) { i += 188; continue }
                if (afc == 3) p += 1 + (d[p].toInt() and 0xFF)
                if (pusi && p < i + 188) p += 1 + (d[p].toInt() and 0xFF) // pointer_field
                val end = i + 188
                if (pusi && p + 8 < end && (pid == 0 || pid in pmtPids)) {
                    val tableId = d[p].toInt() and 0xFF
                    val secLen = ((d[p + 1].toInt() and 0x0F) shl 8) or (d[p + 2].toInt() and 0xFF)
                    val secEnd = minOf(p + 3 + secLen - 4, end)
                    if (pid == 0 && tableId == 0) {
                        var q = p + 8
                        while (q + 4 <= secEnd) {
                            val prog = ((d[q].toInt() and 0xFF) shl 8) or (d[q + 1].toInt() and 0xFF)
                            if (prog != 0) pmtPids += ((d[q + 2].toInt() and 0x1F) shl 8) or (d[q + 3].toInt() and 0xFF)
                            q += 4
                        }
                    } else if (tableId == 2 && p + 12 <= end) {
                        sawPmt = true
                        val infoLen = ((d[p + 10].toInt() and 0x0F) shl 8) or (d[p + 11].toInt() and 0xFF)
                        var q = p + 12 + infoLen
                        while (q + 5 <= secEnd) {
                            val st = d[q].toInt() and 0xFF
                            val esLen = ((d[q + 3].toInt() and 0x0F) shl 8) or (d[q + 4].toInt() and 0xFF)
                            if (st in setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x82, 0x83, 0x84, 0x87, 0x1C)) audio = true
                            if (st == 0x06) { // özel veri: AC-3/E-AC-3/DTS/Opus tanımlayıcısı varsa ses
                                var r = q + 5
                                while (r + 2 <= minOf(q + 5 + esLen, secEnd)) {
                                    val tag = d[r].toInt() and 0xFF
                                    if (tag == 0x6A || tag == 0x7A || tag == 0x7B || tag == 0x7F) audio = true
                                    if (tag == 0x05 && r + 6 <= secEnd && String(d, r + 2, 4, Charsets.ISO_8859_1) in setOf("AC-3", "EAC3", "Opus", "DTS1", "DTS2", "DTS3")) audio = true
                                    r += 2 + (d[r + 1].toInt() and 0xFF)
                                }
                            }
                            q += 5 + esLen
                        }
                        return audio
                    }
                }
                i += 188
            }
            return if (sawPmt) audio else null
        }
        // fMP4: hdlr kutularındaki izleyici türü
        var found = false
        var i = 0
        while (i + 20 <= d.size) {
            if (d[i] == 'h'.code.toByte() && d[i + 1] == 'd'.code.toByte() && d[i + 2] == 'l'.code.toByte() && d[i + 3] == 'r'.code.toByte()) {
                val h = String(d, i + 12, 4, Charsets.ISO_8859_1)
                if (h == "soun") return true
                if (h == "vide") found = true
            }
            i++
        }
        return if (found) false else null
    }

    fun resolve(base: String, rel: String): String = try { URI(base).resolve(rel.trim()).toString() } catch (e: Exception) { rel }

    fun attrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(s).forEach {
            out[it.groupValues[1]] = it.groupValues[2].removeSurrounding("\"")
        }
        return out
    }

    private val drmFormats = Regex("(?i)widevine|playready|streamingkeydelivery|fairplay|urn:uuid")

    fun parseMaster(text: String, baseUrl: String): Master {
        val variants = ArrayList<Variant>()
        val media = ArrayList<Rendition>()
        var drm = false
        var pending: Map<String, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = attrs(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attrs(line.substringAfter(':'))
                    val lang = Lang.normalize(a["LANGUAGE"]).ifEmpty { Lang.normalize(a["NAME"]) }
                    media += Rendition(a["TYPE"]?.lowercase() ?: "", a["GROUP-ID"] ?: "", lang, a["NAME"] ?: "",
                        a["URI"]?.let { resolve(baseUrl, it) } ?: "", a["DEFAULT"] == "YES")
                }
                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    if ((a["METHOD"] ?: "NONE") !in setOf("NONE", "AES-128") || drmFormats.containsMatchIn(a["KEYFORMAT"] ?: "")) drm = true
                }
                !line.startsWith("#") && pending != null -> {
                    val p = pending!!
                    val res = (p["RESOLUTION"] ?: "").split('x')
                    variants += Variant(resolve(baseUrl, line), (p["AVERAGE-BANDWIDTH"] ?: p["BANDWIDTH"] ?: "0").toLongOrNull() ?: 0,
                        res.getOrNull(0)?.toIntOrNull() ?: 0, res.getOrNull(1)?.toIntOrNull() ?: 0, p["CODECS"] ?: "", p["AUDIO"] ?: "", p["SUBTITLES"] ?: "")
                    pending = null
                }
            }
        }
        val uniq = variants.distinctBy { it.url }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
        return Master(uniq, media, drm)
    }

    fun parseMedia(text: String, baseUrl: String): Media {
        val segs = ArrayList<Segment>()
        var dur = 0.0
        var key: Key? = null
        var map: String? = null
        var mapRange: LongArray? = null
        var disc = false
        var segDur = 0.0
        var range: LongArray? = null
        var rangeEnd = 0L
        var seq = 0L
        var mediaSeq = 0L
        var end = false
        var drm = false
        var inCue = false
        var cueLeft = 0.0
        var target = 6.0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val tag = line.substringBefore(':').uppercase()
                val v = line.substringAfter(':', "")
                when (tag) {
                    "#EXTINF" -> segDur = v.substringBefore(',').toDoubleOrNull() ?: 0.0
                    "#EXT-X-MEDIA-SEQUENCE" -> { mediaSeq = v.trim().toLongOrNull() ?: 0; seq = mediaSeq }
                    "#EXT-X-TARGETDURATION" -> target = v.trim().toDoubleOrNull() ?: 6.0
                    "#EXT-X-ENDLIST" -> end = true
                    "#EXT-X-DISCONTINUITY" -> disc = true
                    "#EXT-X-BYTERANGE" -> {
                        val parts = v.split('@')
                        val len = parts[0].trim().toLong()
                        val start = parts.getOrNull(1)?.trim()?.toLong() ?: rangeEnd
                        range = longArrayOf(start, start + len - 1)
                    }
                    "#EXT-X-KEY" -> {
                        val a = attrs(v)
                        val m = a["METHOD"] ?: "NONE"
                        key = if (m == "NONE") null else Key(m, resolve(baseUrl, a["URI"] ?: ""), a["IV"])
                        if (m != "NONE" && (m != "AES-128" || (a["KEYFORMAT"] != null && a["KEYFORMAT"] != "identity"))) drm = true
                    }
                    "#EXT-X-MAP" -> {
                        val a = attrs(v)
                        map = resolve(baseUrl, a["URI"] ?: "")
                        mapRange = a["BYTERANGE"]?.let { br ->
                            val p = br.split('@'); val s = p.getOrNull(1)?.toLong() ?: 0L; longArrayOf(s, s + p[0].toLong() - 1)
                        }
                    }
                    "#EXT-X-CUE-OUT" -> { inCue = true; cueLeft = Regex("[\\d.]+").find(v)?.value?.toDoubleOrNull() ?: 0.0 }
                    "#EXT-X-CUE-OUT-CONT" -> inCue = true
                    "#EXT-X-CUE-IN" -> { inCue = false; cueLeft = 0.0 }
                }
            } else {
                val s = Segment(resolve(baseUrl, line), segDur, range, key, map, mapRange, disc, seq++, inCue)
                range?.let { rangeEnd = it[1] + 1 }
                segs += s
                dur += segDur
                if (inCue && cueLeft > 0) { cueLeft -= segDur; if (cueLeft <= 0.05) inCue = false }
                segDur = 0.0; range = null; disc = false
            }
        }
        markAdGroups(segs)
        return Media(segs, dur, !end, drm, target)
    }

    private val adPath = Regex("(?i)(^|[/_\\-.?=&])(ads?|adv|advert|commercials?|preroll|midroll|postroll|adbreak|vast|reklam)([/_\\-.?=&\\d]|$)")

    /** Kesintiyle ayrılmış, ana içerikten farklı sunucudan gelen kısa grupları reklam say. */
    fun markAdGroups(segs: List<Segment>) {
        val groups = ArrayList<MutableList<Segment>>()
        for (s in segs) { if (groups.isEmpty() || s.discontinuity) groups.add(ArrayList()); groups.last().add(s) }
        if (groups.size < 2) return
        fun host(u: String) = try { URI(u).host ?: "" } catch (e: Exception) { "" }
        val totals = HashMap<String, Double>()
        for (g in groups) totals.merge(host(g[0].url), g.sumOf { it.duration }, Double::plus)
        val mainHost = totals.maxByOrNull { it.value }?.key ?: return
        val mainPathAd = adPath.containsMatchIn(URI(groups.first { host(it[0].url) == mainHost }[0].url).path ?: "")
        for (g in groups) {
            val d = g.sumOf { it.duration }
            val h = host(g[0].url)
            val path = try { URI(g[0].url).path ?: "" } catch (e: Exception) { "" }
            if (d <= 150 && (h != mainHost || (!mainPathAd && adPath.containsMatchIn(path)))) g.forEach { it.isAd = true }
        }
    }
}
