package com.movietrack.app.util

/**
 * Dosya adı ya da sayfa başlığından dizi adı / sezon / bölüm tespiti.
 * Örn. "Breaking.Bad.S02E05.1080p.mp4" → Breaking Bad, 2, 5; "Kuruluş Osman 150. Bölüm izle" → 1, 150.
 */
object EpisodeParser {
    data class Info(val series: String, val season: Int?, val episode: Int?, val isEpisode: Boolean, val title: String, val year: String = "")

    private const val EP = "(?:b[öo]l[üu]m|episode|ep|epis[oó]dio|cap[ií]tulo|folge)"
    private const val SE = "(?:sezon|season|temporada|staffel|saison)"

    private val pairs = listOf(
        Regex("(?i)\\bS(\\d{1,2})\\s*[.\\-_ ]?\\s*E(\\d{1,4})\\b"),
        Regex("(?i)(\\d{1,2})\\s*\\.?\\s*$SE[\\s\\-_.,:]*(\\d{1,4})\\s*\\.?\\s*$EP"),
        Regex("(?i)$SE[\\s\\-_.:#]*(\\d{1,2})[\\s\\S]{0,24}?$EP[\\s\\-_.:#]*(\\d{1,4})"),
        Regex("(?i)(\\d{1,2})\\s*\\.?\\s*$SE[\\s\\S]{0,24}?$EP[\\s\\-_.:#]*(\\d{1,4})"),
        Regex("\\b(\\d{1,2})x(\\d{2,3})\\b"),
    )
    private val epOnly = listOf(
        Regex("(?i)(\\d{1,4})\\s*\\.?\\s*$EP\\b"),
        Regex("(?i)$EP[\\s\\-_.:#]*(\\d{1,4})\\b"),
    )
    private val junk = Regex("(?i)\\b(izle|full\\s*hd|t[üu]rk[cç]e\\s*(dublaj|altyaz[ıi]l[ıi])|altyaz[ıi]l[ıi]|dublaj|online|watch|1080p|720p|480p|2160p|4k|web-?dl|webrip|bluray|brrip|hdrip|x264|x265|h264|h265|hevc|aac|ddp?5\\.1|10bit|hdr)\\b")

    private fun clean(s: String) = s.replace(junk, " ")
        .replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim(' ', '-', '–', '—', '|', ':', '·', ',', '.', '(', '[')
        .trim()

    fun parse(raw: String): Info {
        // Dosya adındaki uzantıyı ve ayraç noktalarını boşluğa çevir
        val noExt = raw.replace(Regex("\\.(mp4|mkv|avi|webm|mov|ts|m4v|srt|vtt)$", RegexOption.IGNORE_CASE), "")
        val text = if (!noExt.contains(' ') && noExt.count { it == '.' || it == '_' } >= 2) noExt.replace('.', ' ').replace('_', ' ') else noExt
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(text)?.value ?: ""
        for (re in pairs) {
            val m = re.find(text) ?: continue
            val series = clean(text.substring(0, m.range.first).split(Regex("\\s+[|–—]\\s+")).last())
            return Info(series.ifEmpty { clean(text.substring(m.range.last + 1)) }, m.groupValues[1].toInt(), m.groupValues[2].toInt(), true, series)
        }
        for (re in epOnly) {
            val m = re.find(text) ?: continue
            val series = clean(text.substring(0, m.range.first))
            return Info(series, 1, m.groupValues[1].toInt(), true, series)
        }
        var t = text
        if (year.isNotEmpty()) t = t.substringBefore(year)
        val title = clean(t.split(Regex("\\s+[|–—-]\\s+")).first()).ifEmpty { clean(text) }
        return Info(title, null, null, false, if (year.isNotEmpty()) "$title ($year)" else title, year)
    }
}
