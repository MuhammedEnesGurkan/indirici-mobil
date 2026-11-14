package com.movietrack.app

import com.movietrack.app.download.Hls
import com.movietrack.app.download.Errors.Kind as ErrKind
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
        val w = EpisodeParser.parse("Dizipal The Walking Dead 1.Sezon 1.Bölüm izle | Türkçe Dublaj ve Altyazılı", "dizipal1234.com")
        assertEquals("The Walking Dead", w.series); assertEquals(1, w.season); assertEquals(1, w.episode)
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
    fun fakeImageHeaderIsStripped() {
        val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 1 }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(200) { 7 }
        val out = Hls.stripJunk(png + ts)
        assertEquals(ts.size, out.size)
        assertEquals(0x47.toByte(), out[0])
        assertEquals(ts.size, Hls.stripJunk(ts).size)
    }

    @Test
    fun detectsSilentSegments() {
        fun pkt(pid: Int, payload: ByteArray): ByteArray {
            val b = ByteArray(188) { 0xFF.toByte() }
            b[0] = 0x47; b[1] = (0x40 or (pid shr 8)).toByte(); b[2] = pid.toByte(); b[3] = 0x10; b[4] = 0
            payload.copyInto(b, 5)
            return b
        }
        val pat = pkt(0, byteArrayOf(0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xF0.toByte(), 0x00, 0, 0, 0, 0))
        fun pmt(vararg streams: Int): ByteArray {
            val es = streams.flatMapIndexed { i, st -> listOf(st, 0xE1, 0x00 + i, 0xF0, 0x00) }.map { it.toByte() }
            val len = 9 + es.size + 4
            val head = listOf(0x02, 0xB0, len, 0, 1, 0xC1, 0, 0, 0xE1, 0x00, 0xF0, 0x00).map { it.toByte() }
            return pkt(0x1000, (head + es + listOf<Byte>(0, 0, 0, 0)).toByteArray())
        }
        assertEquals(false, Hls.segmentHasAudio(pat + pmt(0x1B) + pkt(0x100, ByteArray(10))))
        assertEquals(true, Hls.segmentHasAudio(pat + pmt(0x1B, 0x0F) + pkt(0x100, ByteArray(10))))
        // sahte resim başlığıyla da çalışmalı
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(100) { 7 }
        assertEquals(false, Hls.segmentHasAudio(png + pat + pmt(0x1B) + pkt(0x100, ByteArray(10))))
        // fMP4 init: hdlr vide / soun
        fun hdlr(t: String) = byteArrayOf(0, 0, 0, 33) + "hdlr".toByteArray() + ByteArray(8) + t.toByteArray() + ByteArray(13)
        assertEquals(false, Hls.segmentHasAudio(hdlr("vide")))
        assertEquals(true, Hls.segmentHasAudio(hdlr("vide") + hdlr("soun")))
        assertEquals(null, Hls.segmentHasAudio(ByteArray(50)))
        val v = Hls.Variant("u", 1, 0, 0, "avc1.64001f", "", "")
        assertEquals(false, Hls.variantHasAudio(v))
        assertEquals(true, Hls.variantHasAudio(v.copy(codecs = "avc1.64001f,mp4a.40.2")))
        assertEquals(null, Hls.variantHasAudio(v.copy(codecs = "")))
    }

    @Test
    fun languages() {
        assertEquals("tr", Lang.normalize("tur"))
        assertEquals("en", Lang.normalize("en-US"))
        assertEquals("aud:de", Lang.audLabel("German"))
        assertEquals("tur", Lang.toIso3("tr"))
        assertEquals("en", Lang.fromFileName("https://hdfilmcehennemi.mobi/s/6DwLAfpoSGn-eng-neverafterdark.vtt"))
        assertEquals("pt", Lang.fromFileName("https://x/6DwLAfpoSGn-pt-sd-144426-3264.vtt?a=1"))
        assertEquals("tr", Lang.fromFileName("https://x/tr.vtt"))
        assertEquals("", Lang.fromFileName("https://x/abc-sd-1080.vtt"))
    }

    @Test
    fun stripsFakeImageHeaderFromRawAudio() {
        val aac = java.io.File("src/androidTest/assets/fx/aac/a1.aac").readBytes()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200) { (it * 7).toByte() }
        val out = com.movietrack.app.download.Hls.stripJunk(png + aac)
        org.junit.Assert.assertArrayEquals("sahte başlık atılıp ses (ID3) kalmalı", aac, out)
        // Temiz ses olduğu gibi kalmalı (yanlışlıkla kesilmemeli)
        org.junit.Assert.assertArrayEquals(aac, com.movietrack.app.download.Hls.stripJunk(aac))
        val adts = aac.copyOfRange(10 + (((aac[6].toInt() and 0x7F) shl 21) or ((aac[7].toInt() and 0x7F) shl 14) or ((aac[8].toInt() and 0x7F) shl 7) or (aac[9].toInt() and 0x7F)), aac.size)
        org.junit.Assert.assertArrayEquals(adts, com.movietrack.app.download.Hls.stripJunk(png + adts))
    }

    @Test
    fun youtubeLinks() {
        val y = com.movietrack.app.download.YouTube
        assertEquals("jNQXAC9IVRw", y.id("https://www.youtube.com/watch?v=jNQXAC9IVRw&t=3"))
        assertEquals("jNQXAC9IVRw", y.id("https://m.youtube.com/watch?feature=share&v=jNQXAC9IVRw"))
        assertEquals("jNQXAC9IVRw", y.id("https://youtu.be/jNQXAC9IVRw?si=x"))
        assertEquals("jNQXAC9IVRw", y.id("https://www.youtube.com/shorts/jNQXAC9IVRw"))
        assertEquals(null, y.id("https://www.youtube.com/"))
        assertEquals(null, y.id("https://example.com/watch?v=jNQXAC9IVRw"))
        assertEquals(12345L, y.size("https://r1.googlevideo.com/videoplayback?itag=137&clen=12345&dur=19"))
    }

    @Test
    fun errorMessagesAreFriendlyAndHideInternals() {
        val E = com.movietrack.app.download.Errors
        // Kullanıcının ağı
        assertEquals(ErrKind.NETWORK, E.classify(java.net.SocketTimeoutException("timeout")).kind)
        assertEquals(ErrKind.NETWORK, E.classify(java.net.UnknownHostException("cdn.example.com")).kind)
        assertEquals(ErrKind.NETWORK, E.classify(java.net.SocketException("Connection reset")).kind)
        assertEquals(ErrKind.NETWORK, E.classify(java.io.IOException("x", java.net.ConnectException("refused"))).kind)
        // Sunucu
        val s403 = E.classify(com.movietrack.app.download.HttpException(403, "https://cdn.example.com/a.ts"))
        assertEquals(ErrKind.SERVER, s403.kind); assertTrue(s403.message.contains("süresi"))
        assertTrue(E.message(com.movietrack.app.download.HttpException(429, "u")).contains("yoğun"))
        // Depolama
        assertEquals(ErrKind.STORAGE, E.classify(java.io.IOException("write failed: ENOSPC (No space left on device)")).kind)
        // Kaynak (bizim açıklayıcı mesajımız olduğu gibi)
        assertEquals("Bu yayın DRM ile korunuyor; indirilemez.", E.message(com.movietrack.app.download.UserError("Bu yayın DRM ile korunuyor; indirilemez.")))
        // Tanımadığımız her şey: sistem hatası, iç ayrıntı ekranda yok ama raporda var
        val sys = E.classify(IllegalStateException("Failed to stop the muxer at /data/user/0/com.indirici.app/files/streams/7"))
        assertEquals(ErrKind.SYSTEM, sys.kind)
        assertEquals(E.SYSTEM_MESSAGE, sys.message)
        assertFalse(sys.message.contains("/data")); assertFalse(sys.message.contains("muxer"))
        assertTrue(sys.technical.contains("IllegalStateException"))
        // Tanı raporu yığın izini ve neden zincirini taşır
        val st = E.stack(RuntimeException("dış", java.io.IOException("iç neden")))
        assertTrue(st.contains("java.lang.RuntimeException: dış")); assertTrue(st.contains("neden: java.io.IOException: iç neden"))
        assertTrue(st.contains("    at "))
        val npe = E.classify(NullPointerException("Attempt to invoke virtual method on a null object reference"))
        assertEquals(E.SYSTEM_MESSAGE, npe.message)
    }

    @Test fun headerlessVttBecomesValidSrt() {
        val vtt = "1\n00:01.500 --> 00:03.000\nMerhaba\n\n00:00:04.000 --> 00:00:05.250 align:start\n<c.yellow>Dünya</c>\n"
        val srt = com.movietrack.app.download.Subtitles.toSrt(com.movietrack.app.download.Subtitles.parseVtt(vtt).first)
        assertTrue(srt, srt.contains("00:00:01,500 --> 00:00:03,000\nMerhaba"))
        assertTrue(srt, srt.contains("00:00:04,000 --> 00:00:05,250\nDünya"))
    }
}
