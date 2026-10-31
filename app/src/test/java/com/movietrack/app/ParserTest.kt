package com.movietrack.app

import com.movietrack.app.download.Hls
import com.movietrack.app.util.EpisodeParser
import com.movietrack.app.util.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun episodeFromFileNameAndTitle() {
        val a = EpisodeParser.parse("Breaking.Bad.S02E05.1080p.WEB-DL.mp4")
        assertTrue(a.isEpisode); assertEquals("Breaking Bad", a.series); assertEquals(2, a.season); assertEquals(5, a.episode)
        val b = EpisodeParser.parse("Kuruluş Osman 150. Bölüm izle")
        assertEquals("Kuruluş Osman", b.series); assertEquals(150, b.episode)
        val c = EpisodeParser.parse("Yalı Çapkını 2. Sezon 14. Bölüm")
        assertEquals("Yalı Çapkını", c.series); assertEquals(2, c.season); assertEquals(14, c.episode)
        val d = EpisodeParser.parse("Inception (2010) 1080p.mkv")
        assertFalse(d.isEpisode); assertEquals("Inception", d.series); assertEquals("2010", d.year)
    }

    @Test
    fun hlsMasterAndAds() {
        val m = Hls.parseMaster(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",LANGUAGE=\"tur\",NAME=\"Türkçe\",URI=\"tr.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,AUDIO=\"a\"\n720.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO=\"a\"\n1080.m3u8\n",
            "https://cdn.site.com/v/master.m3u8",
        )
        assertEquals(listOf(1080, 720), m.variants.map { it.height })
        assertEquals("tr", m.media[0].lang)
        assertEquals("https://cdn.site.com/v/tr.m3u8", m.media[0].url)

        val pl = Hls.parseMedia(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\n#EXTINF:10,\n1.ts\n#EXTINF:10,\n2.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:15,\n" +
                "https://ads.other.net/a1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10,\n3.ts\n#EXT-X-ENDLIST\n",
            "https://cdn.site.com/v/index.m3u8",
        )
        assertEquals(listOf(false, false, true, false), pl.segments.map { it.isAd })
        assertEquals("AES-128", pl.segments[0].key?.method)
        assertFalse(pl.live)
        assertEquals(45.0, pl.duration, 0.01)
    }

    @Test
    fun languages() {
        assertEquals("tr", Lang.normalize("tur"))
        assertEquals("en", Lang.normalize("en-US"))
        assertEquals("aud:de", Lang.audLabel("German"))
        assertEquals("tur", Lang.toIso3("tr"))
    }
}
