package com.movietrack.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.movietrack.app.data.Status
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.OutputFile
import com.movietrack.app.download.Requests
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Gerçek YouTube'a karşı uçtan uca (CI'da internet varken; YouTube veri merkezi adreslerini engelleyebilir). */
@RunWith(AndroidJUnit4::class)
class YouTubeLiveTest {
    private val app get() = App.instance

    @Test
    fun downloadsShortPublicVideo() = runBlocking {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            app.settings.wifiOnly = false; app.settings.folderUri = null
            val u = "https://www.youtube.com/watch?v=jNQXAC9IVRw" // "Me at the zoo", 19 sn
            val i = try { Analyzer.analyze(u, null, null, null) } catch (t: Throwable) { Log.i("YouTubeLive", "analiz hatası: ${t.message}"); throw t }
            Log.i("YouTubeLive", "başlık=${i.fileName} kaliteler=${i.qualities.map { it.label }} sesler=${i.audios.map { it.name }} altyazı=${i.subtitles.size}")
            assertTrue("kalite yok", i.qualities.isNotEmpty())
            val req = Requests.build(i, u, "yt-test-${System.currentTimeMillis()}", "Test", i.qualities.last().id, i.audios.take(1).map { it.id }, emptyList())
            val id = app.downloads.add(req)
            val dao = app.db.downloads()
            val deadline = System.currentTimeMillis() + 120_000
            while (true) {
                val e = dao.get(id)!!
                if (e.status == Status.DONE) {
                    val ex = MediaExtractor()
                    ex.setDataSource(app, OutputFile.decode(e.outputUri)!!.uri, null)
                    var v = 0; var a = 0; var d = 0L
                    for (k in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(k); val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                        if (m.startsWith("video/")) v++; if (m.startsWith("audio/")) a++
                        if (f.containsKey(MediaFormat.KEY_DURATION)) d = maxOf(d, f.getLong(MediaFormat.KEY_DURATION))
                    }
                    ex.release()
                    Log.i("YouTubeLive", "TAMAM görüntü=$v ses=$a süre=${d / 1e6}s boyut=${e.totalBytes} uyarı=${e.error}")
                    assertTrue(v == 1 && a >= 1 && d > 15_000_000)
                    break
                }
                if (e.status == Status.ERROR) { Log.i("YouTubeLive", "indirme hatası: ${e.error}"); fail(e.error) }
                if (System.currentTimeMillis() > deadline) fail("zaman aşımı")
                delay(500)
            }
        } finally { scenario.close() }
    }
}
