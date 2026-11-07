package com.movietrack.app

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.OutputFile
import com.movietrack.app.download.Requests
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gerçek indirme testleri: emülatörde uygulamanın kendi indirme motoru, telefon içinde çalışan
 * yerel bir sunucudan (gerçek sitelerin yaptığı numaralarla) video indirir; çıkan dosya
 * Android'in kendi okuyucusuyla açılıp görüntü + ses izi ve süresi kontrol edilir.
 */
@RunWith(AndroidJUnit4::class)
class DownloadE2ETest {
    @get:Rule
    val perms: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    private val app get() = App.instance
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val server = MockWebServer()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val run = System.currentTimeMillis().toString(36)

    private fun asset(p: String): ByteArray? = try { assets.open("fx/$p").use { it.readBytes() } } catch (_: Exception) { null }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                var path = request.requestUrl!!.encodedPath.removePrefix("/")
                // Dizipal benzeri: Referer olmadan 403
                if (path.startsWith("ref/")) {
                    if (request.getHeader("Referer")?.startsWith("http://site.test/") != true) return MockResponse().setResponseCode(403)
                    path = path.removePrefix("ref/")
                }
                // hdfilmcehennemi benzeri: segmentler .png adıyla ve başlarında sahte PNG başlığıyla
                if (path.startsWith("junk/")) {
                    val n = path.removePrefix("junk/")
                    if (n == "index.m3u8") return ok(String(asset("ts/index.m3u8")!!).replace(".ts", ".png").toByteArray(), "application/vnd.apple.mpegurl", request)
                    val ts = asset("ts/" + n.replace(".png", ".ts")) ?: return MockResponse().setResponseCode(404)
                    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200) { (it * 7).toByte() }
                    return ok(png + ts, "image/png", request)
                }
                // Ses parçaları da sahte PNG başlığıyla: ham AAC (ID3 etiketli) .png adıyla
                if (path.startsWith("junkaud/")) {
                    val n = path.removePrefix("junkaud/")
                    if (n == "master.m3u8") return ok(String(asset("aac/master.m3u8")!!).replace("../split/", "/split/").toByteArray(), "application/vnd.apple.mpegurl", request)
                    if (n == "audio.m3u8") return ok(String(asset("aac/audio.m3u8")!!).replace(".aac", ".png").toByteArray(), "application/vnd.apple.mpegurl", request)
                    val a = asset("aac/" + n.replace(".png", ".aac")) ?: return MockResponse().setResponseCode(404)
                    val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200) { (it * 7).toByte() }
                    return ok(png + a, "image/png", request)
                }
                // gizlenmiş liste adı: master.txt
                if (path == "hidden/master.txt") {
                    val t = String(asset("ts/master.m3u8")!!).replace("\nindex.m3u8", "\n/ts/index.m3u8").replace("../split/", "/split/")
                    return ok(t.toByteArray(), "text/plain", request)
                }
                val body = asset(path) ?: return MockResponse().setResponseCode(404)
                val type = when (path.substringAfterLast('.')) {
                    "m3u8" -> "application/vnd.apple.mpegurl"; "mpd" -> "application/dash+xml"; "ts" -> "video/mp2t"
                    "mp4", "m4s" -> "video/mp4"; "vtt" -> "text/vtt"; else -> "application/octet-stream"
                }
                return ok(body, type, request)
            }
        }
        server.start()
        app.settings.wifiOnly = false
        app.settings.folderUri = null
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        server.shutdown()
    }

    /** Range isteklerini de destekleyen yanıt (çok parçalı indirme için). */
    private fun ok(body: ByteArray, type: String, r: RecordedRequest): MockResponse {
        val range = r.getHeader("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        if (range != null) {
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
            if (start >= body.size) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(206).setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                .setBody(Buffer().write(body, start.toInt(), (end - start + 1).toInt()))
        }
        return MockResponse().setHeader("Content-Type", type).setHeader("Accept-Ranges", "bytes").setBody(Buffer().write(body))
    }

    private fun url(p: String) = server.url("/$p").toString()

    data class Probe(val video: Int, val audio: Int, val durationSec: Double, val audioLangs: List<String>, val audioSec: Double = 0.0)

    private fun probe(uri: Uri): Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(app, uri, null)
            var v = 0; var a = 0; var d = 0L; var ad = 0L
            val langs = ArrayList<String>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) v++
                if (mime.startsWith("audio/")) { a++; f.getString(MediaFormat.KEY_LANGUAGE)?.let { langs += it }; if (f.containsKey(MediaFormat.KEY_DURATION)) ad = maxOf(ad, f.getLong(MediaFormat.KEY_DURATION)) }
                if (f.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, f.getLong(MediaFormat.KEY_DURATION))
            }
            return Probe(v, a, d / 1e6, langs, ad / 1e6)
        } finally { ex.release() }
    }

    private fun analyze(u: String, kind: String? = null, referer: String? = null) = runBlocking { Analyzer.analyze(u, kind, referer, null) }

    private fun download(i: Analyzer.Info, u: String, name: String, quality: String? = i.qualities.firstOrNull()?.id,
                         audios: List<String> = i.audios.map { it.id }, subs: List<String> = emptyList(), mode: String = "mp4", referer: String? = null): DownloadEntity = runBlocking {
        val req = Requests.build(i, u, "test-$name-$run", "Test", quality, audios, subs, mode, referer = referer)
        val id = app.downloads.add(req)
        val dao = app.db.downloads()
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val e = dao.get(id)!!
            if (e.status == Status.DONE) return@runBlocking e
            if (e.status == Status.ERROR) fail("$name indirilemedi: ${e.error}")
            delay(300)
        }
        fail("$name zaman aşımı: ${dao.get(id)?.status}"); throw IllegalStateException()
    }

    private fun output(e: DownloadEntity): Uri = OutputFile.decode(e.outputUri)!!.uri

    private fun assertPlayable(e: DownloadEntity, what: String, wantAudio: Boolean = true) {
        val p = probe(output(e))
        assertEquals("$what: görüntü izi (uyarı: ${e.error})", 1, p.video)
        if (wantAudio) {
            assertTrue("$what: ses izi yok (${p}, uyarı: ${e.error})", p.audio >= 1)
            // Ses tüm video boyunca sürmeli (yalnızca ilk parçası değil)
            assertTrue("$what: ses ${p.audioSec}s / görüntü ${p.durationSec}s", p.audioSec >= p.durationSec * 0.8)
        }
        assertTrue("$what: süre ${p.durationSec}s", p.durationSec in 5.0..7.5)
    }

    @Test
    fun hlsTsWithMuxedAudio() {
        val u = url("ts/index.m3u8")
        val i = analyze(u)
        assertEquals("hls", i.kind)
        assertEquals(true, i.qualities.single().audio)
        assertEquals(6.0, i.duration, 0.1)
        assertPlayable(download(i, u, "ts"), "TS")
    }

    @Test
    fun masterMarksSilentVariant() {
        val u = url("ts/master.m3u8")
        val i = analyze(u)
        assertEquals(2, i.qualities.size)
        val silent = i.qualities.first { it.id.endsWith("split/video.m3u8") }
        val loud = i.qualities.first { it.id.endsWith("ts/index.m3u8") }
        assertEquals(false, silent.audio)
        assertEquals(true, loud.audio)
        // tek başına görüntü listesi (diziyou'daki sessiz 720p.m3u8 gibi)
        assertEquals(false, analyze(url("split/video.m3u8")).qualities.single().audio)
        assertPlayable(download(i, u, "master-loud", quality = loud.id), "Ana liste sesli kalite")
    }

    @Test
    fun separateAudioTrackIsMergedWithLanguage() {
        val u = url("split/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        assertEquals("tr", i.audios[0].lang)
        val e = download(i, u, "split")
        assertPlayable(e, "Ayrı ses izi")
        assertTrue("dil etiketi: ${probe(output(e)).audioLangs}", probe(output(e)).audioLangs.any { it == "tur" })
    }

    /** Ham .aac ses parçaları, her birinin başında ID3 zaman etiketi (Apple tarzı "packed audio"). */
    @Test
    fun packedAacAudioWithId3() {
        val u = url("aac/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        val e = download(i, u, "aac")
        assertPlayable(e, "Ham AAC ses")
    }

    /** Ayrı ses izi ham AAC ve parçaları sahte resim gibi gizlenmiş (hdfilmcehennemi tarzı). */
    @Test
    fun disguisedPackedAudio() {
        val u = url("junkaud/master.m3u8")
        val i = analyze(u)
        assertEquals(1, i.audios.size)
        val e = download(i, u, "junkaud")
        assertPlayable(e, "Gizlenmiş ham AAC ses")
    }

    /** Seçilen ayrı ses izi işe yaramıyorsa (içinde ses yok) videonun kendi sesi kullanılmalı. */
    @Test
    fun brokenSeparateAudioFallsBackToMuxed() {
        val u = url("brokenaud/master.m3u8")
        val i = analyze(u)
        val e = download(i, u, "brokenaud")
        assertPlayable(e, "Boş ayrı ses izi")
    }

    @Test
    fun separateModeWritesAudioFile() {
        val u = url("split/master.m3u8")
        val i = analyze(u)
        val e = download(i, u, "sep", mode = "separate")
        assertPlayable(e, "Ayrı dosya video", wantAudio = false)
        val extra = e.extraOutputs.lines().filter { it.isNotBlank() }
        assertTrue("ses dosyası: $extra", extra.any { it.endsWith("aud:tr") })
    }

    @Test
    fun fakeImageHeadersAreStripped() {
        val u = url("junk/index.m3u8")
        val i = analyze(u)
        assertEquals(true, i.qualities.single().audio)
        assertPlayable(download(i, u, "junk"), "Sahte PNG başlıklı")
    }

    @Test
    fun hiddenTxtPlaylist() {
        val u = url("hidden/master.txt")
        val i = analyze(u, kind = "hls")
        assertEquals(2, i.qualities.size)
        assertPlayable(download(i, u, "hidden", quality = i.qualities.first { it.audio == true }.id), "master.txt")
    }

    @Test
    fun fmp4Hls() {
        val u = url("fmp4/index.m3u8")
        val i = analyze(u)
        assertEquals(true, i.qualities.single().audio)
        assertPlayable(download(i, u, "fmp4"), "fMP4")
    }

    @Test
    fun aes128Hls() {
        val u = url("aes/index.m3u8")
        val i = analyze(u)
        assertTrue(i.encrypted)
        assertPlayable(download(i, u, "aes"), "AES-128")
    }

    @Test
    fun dash() {
        val u = url("dash/manifest.mpd")
        val i = analyze(u)
        assertEquals("dash", i.kind)
        assertTrue(i.qualities.isNotEmpty())
        assertTrue(i.audios.isNotEmpty())
        assertPlayable(download(i, u, "dash"), "DASH")
    }

    @Test
    fun directMp4NeedsReferer() {
        val u = url("ref/direct.mp4")
        val i = analyze(u, referer = "http://site.test/film")
        assertEquals("direct", i.kind)
        assertEquals(asset("direct.mp4")!!.size.toLong(), i.size)
        val e = download(i, u, "direct", referer = "http://site.test/film")
        val bytes = app.contentResolver.openInputStream(output(e))!!.use { it.readBytes() }
        assertTrue("dosya aynı değil", bytes.contentEquals(asset("direct.mp4")!!))
        assertPlayable(e, "Doğrudan MP4")
    }

    @Test
    fun subtitleSavedNextToVideo() {
        val u = url("ts/index.m3u8")
        var i = analyze(u)
        val sub = url("sub.vtt")
        i = i.copy(subtitles = i.subtitles + Analyzer.Track(sub, "tr", "sub.vtt", "subtitle:tr"))
        val e = download(i, u, "sub", subs = listOf(sub))
        val srt = e.extraOutputs.lines().firstOrNull { it.endsWith("subtitle:tr") }
        assertFalse("altyazı kaydedilmedi: ${e.extraOutputs}", srt == null)
        val text = app.contentResolver.openInputStream(OutputFile.decode(srt!!.substringBeforeLast('|'))!!.uri)!!.use { String(it.readBytes()) }
        assertTrue(text, text.contains("Merhaba") && text.contains("00:00:00,500 --> 00:00:02,000"))
    }
}
