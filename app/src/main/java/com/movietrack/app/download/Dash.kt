package com.movietrack.app.download

import com.movietrack.app.util.Lang
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** MPEG-DASH (MPD) ayrıştırıcı: SegmentTemplate ($Number$/$Time$/Timeline), SegmentList, SegmentBase. */
object Dash {
    data class Rep(
        val id: String, val kind: String, val bandwidth: Long, val width: Int, val height: Int, val codecs: String, val lang: String,
        val label: String, val init: String?, val segments: List<Hls.Segment>,
    )
    data class Mpd(val reps: List<Rep>, val duration: Double, val drm: Boolean)

    fun parseDuration(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val m = Regex("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").find(s) ?: return 0.0
        fun g(i: Int) = m.groupValues[i].toDoubleOrNull() ?: 0.0
        return g(1) * 86400 + g(2) * 3600 + g(3) * 60 + g(4)
    }

    private fun Element.kids(name: String) = (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().filter { it.localName == name || it.tagName.substringAfter(':') == name }

    private fun Element.kid(name: String) = kids(name).firstOrNull()

    private fun fill(tpl: String, vars: Map<String, String>) = Regex("\\$(\\w*)(?:%0(\\d+)d)?\\$").replace(tpl) { m ->
        val name = m.groupValues[1]
        if (name.isEmpty()) "$" else vars[name]?.let { v -> m.groupValues[2].toIntOrNull()?.let { v.padStart(it, '0') } ?: v } ?: m.value
    }

    fun parse(text: String, mpdUrl: String): Mpd {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray()))
        val root = doc.documentElement
        val total = parseDuration(root.getAttribute("mediaPresentationDuration"))
        var drm = false
        val reps = ArrayList<Rep>()
        val periods = root.kids("Period")
        // Basitlik için en uzun periyot (reklam periyotlarını atlar)
        val period = periods.maxByOrNull { parseDuration(it.getAttribute("duration")).takeIf { d -> d > 0 } ?: total } ?: return Mpd(emptyList(), total, false)
        val periodDur = parseDuration(period.getAttribute("duration")).takeIf { it > 0 } ?: total
        fun base(vararg els: Element?): String {
            var b = mpdUrl
            for (el in els) el?.kid("BaseURL")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { b = Hls.resolve(b, it) }
            return b
        }
        for (asEl in period.kids("AdaptationSet")) {
            if (asEl.getElementsByTagNameNS("*", "ContentProtection").length > 0) drm = true
            val asMime = asEl.getAttribute("mimeType")
            val asType = asEl.getAttribute("contentType")
            val lang = Lang.normalize(asEl.getAttribute("lang"))
            val label = asEl.kid("Label")?.textContent ?: ""
            for (rep in asEl.kids("Representation")) {
                val mime = rep.getAttribute("mimeType").ifEmpty { asMime }
                val codecs = rep.getAttribute("codecs").ifEmpty { asEl.getAttribute("codecs") }
                val kind = when {
                    asType == "video" || mime.startsWith("video") -> "video"
                    asType == "audio" || mime.startsWith("audio") -> "audio"
                    asType == "text" || mime.startsWith("text") || codecs.contains("wvtt") || codecs.contains("stpp") -> "text"
                    else -> continue
                }
                val id = rep.getAttribute("id")
                val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: 0
                val b = base(root, period, asEl, rep)
                val vars = mapOf("RepresentationID" to id, "Bandwidth" to bw.toString())
                val tpl = rep.kid("SegmentTemplate") ?: asEl.kid("SegmentTemplate") ?: period.kid("SegmentTemplate")
                val segs = ArrayList<Hls.Segment>()
                var init: String? = null
                if (tpl != null) {
                    val ts = tpl.getAttribute("timescale").toLongOrNull() ?: 1
                    val startNum = tpl.getAttribute("startNumber").toLongOrNull() ?: 1
                    val media = tpl.getAttribute("media")
                    tpl.getAttribute("initialization").takeIf { it.isNotEmpty() }?.let { init = Hls.resolve(b, fill(it, vars)) }
                    val timeline = tpl.kid("SegmentTimeline")
                    var num = startNum
                    if (timeline != null) {
                        var t = 0L
                        val ss = timeline.kids("S")
                        for ((i, s) in ss.withIndex()) {
                            s.getAttribute("t").toLongOrNull()?.let { t = it }
                            val d = s.getAttribute("d").toLong()
                            var r = s.getAttribute("r").toLongOrNull() ?: 0
                            if (r < 0) {
                                val next = ss.getOrNull(i + 1)?.getAttribute("t")?.toLongOrNull()
                                val end = next ?: (t + (periodDur * ts).toLong())
                                r = maxOf(0, (end - t + d - 1) / d - 1)
                            }
                            for (k in 0..r) {
                                segs += Hls.Segment(Hls.resolve(b, fill(media, vars + mapOf("Number" to num.toString(), "Time" to t.toString()))), d.toDouble() / ts, null, null, init, null, false, num)
                                t += d; num++
                            }
                        }
                    } else {
                        val d = tpl.getAttribute("duration").toDoubleOrNull() ?: 0.0
                        if (d > 0) {
                            val count = kotlin.math.ceil(periodDur * ts / d - 1e-6).toLong()
                            for (k in 0 until count) {
                                segs += Hls.Segment(Hls.resolve(b, fill(media, vars + mapOf("Number" to (startNum + k).toString(), "Time" to (k * d).toLong().toString()))), d / ts, null, null, init, null, false, startNum + k)
                            }
                        }
                    }
                } else {
                    val list = rep.kid("SegmentList") ?: asEl.kid("SegmentList")
                    if (list != null) {
                        list.kid("Initialization")?.getAttribute("sourceURL")?.takeIf { it.isNotEmpty() }?.let { init = Hls.resolve(b, it) }
                        val ts = list.getAttribute("timescale").toLongOrNull() ?: 1
                        val d = (list.getAttribute("duration").toDoubleOrNull() ?: 0.0) / ts
                        list.kids("SegmentURL").forEachIndexed { i, su ->
                            val r = su.getAttribute("mediaRange").takeIf { it.contains('-') }?.split('-')?.let { longArrayOf(it[0].toLong(), it[1].toLong()) }
                            segs += Hls.Segment(Hls.resolve(b, su.getAttribute("media")), d, r, null, init, null, false, i.toLong())
                        }
                    } else {
                        // SegmentBase / yalnızca BaseURL: tek dosya
                        segs += Hls.Segment(b, periodDur, null, null, null, null, false, 0)
                    }
                }
                reps += Rep(id, kind, bw, rep.getAttribute("width").toIntOrNull() ?: 0, rep.getAttribute("height").toIntOrNull() ?: 0, codecs, lang, label, init, segs)
            }
        }
        return Mpd(reps.sortedWith(compareByDescending<Rep> { it.height }.thenByDescending { it.bandwidth }), total.takeIf { it > 0 } ?: periodDur, drm)
    }
}
