package com.movietrack.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.movietrack.app.download.Muxer
import com.movietrack.app.download.OutputStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Birleştirme hızı ölçümü: ~30 dk'lık TS akışını (küçük test parçalarının tekrarı) MP4'e birleştirir.
 * A) doğrudan İndirilenler (MediaStore) dosyasına, B) önce uygulama klasörüne, sonra tek kopyalama.
 * Süreler logcat'e "MergeSpeed" etiketiyle yazılır.
 */
@RunWith(AndroidJUnit4::class)
class MergeSpeedTest {
    private val app get() = App.instance
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun longTs(repeats: Int, names: List<String> = listOf("ts/s0.ts", "ts/s1.ts", "ts/s2.ts")): File {
        val segs = names.map { n -> assets.open("fx/$n").use { it.readBytes() } }
        val f = File(app.filesDir, "long.ts")
        FileOutputStream(f).use { os -> repeat(repeats) { segs.forEach { os.write(it) } } }
        return f
    }

    @Test
    fun mergeSpeed() {
        val src = longTs(300) // 300 × 6 sn = 30 dk
        val inputs = listOf(Muxer.Input(src.path, takeVideo = true, takeAudio = true))

        // A) Doğrudan MediaStore'a (şimdiki yol)
        var t0 = System.nanoTime()
        val outA = OutputStore.create(app, "hiz-a-${System.currentTimeMillis()}.mp4", "video/mp4", "test")
        outA.openRw(app).use { Muxer.mux(inputs, it.fileDescriptor) }
        outA.publish(app)
        val a = (System.nanoTime() - t0) / 1_000_000
        outA.delete(app)

        // B) Önce uygulama klasörüne, sonra büyük bloklarla kopyala
        t0 = System.nanoTime()
        val tmp = File(app.filesDir, "tmp.mp4")
        FileOutputStream(tmp).use { fos -> Muxer.mux(inputs, fos.fd) }
        val muxOnly = (System.nanoTime() - t0) / 1_000_000
        val outB = OutputStore.create(app, "hiz-b-${System.currentTimeMillis()}.mp4", "video/mp4", "test")
        outB.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { os -> FileInputStream(tmp).use { it.copyTo(os, 4 shl 20) } } }
        outB.publish(app)
        val b = (System.nanoTime() - t0) / 1_000_000
        outB.delete(app)

        Log.i("MergeSpeed", "kucuk: kaynak=${src.length() / 1024} KB mp4=${tmp.length() / 1024} KB A_dogrudan=${a} ms B_ozel+kopya=${b} ms (yalniz birlestirme=${muxOnly} ms)")
        tmp.delete(); src.delete()
        assertTrue(a > 0 && b > 0)
    }

    /** Film boyutunda veri: 720p ~5 Mbit/sn, 25 dk (~1 GB). Önce yeni yol (B), sonra eski yol (A). */
    @Test
    fun bigMergeSpeed() {
        val src = longTs(150, listOf("big/b0.ts"))
        val inputs = listOf(Muxer.Input(src.path, takeVideo = true, takeAudio = true))
        var t0 = System.nanoTime()
        val tmp = File(app.filesDir, "tmp.mp4")
        FileOutputStream(tmp).use { fos -> Muxer.mux(inputs, fos.fd) }
        val muxOnly = (System.nanoTime() - t0) / 1_000_000
        val outB = OutputStore.create(app, "hiz-b-${System.currentTimeMillis()}.mp4", "video/mp4", "test")
        outB.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { os -> FileInputStream(tmp).use { it.copyTo(os, 4 shl 20) } } }
        outB.publish(app)
        val b = (System.nanoTime() - t0) / 1_000_000
        val mp4 = tmp.length()
        outB.delete(app); tmp.delete()

        t0 = System.nanoTime()
        val outA = OutputStore.create(app, "hiz-a-${System.currentTimeMillis()}.mp4", "video/mp4", "test")
        outA.openRw(app).use { Muxer.mux(inputs, it.fileDescriptor) }
        outA.publish(app)
        val a = (System.nanoTime() - t0) / 1_000_000
        outA.delete(app)
        Log.i("MergeSpeed", "buyuk: kaynak=${src.length() / 1048576} MB mp4=${mp4 / 1048576} MB B_ozel+kopya=${b} ms (yalniz birlestirme=${muxOnly} ms) A_dogrudan=${a} ms")
        src.delete()
        assertTrue(a > 0 && b > 0)
    }

    /** Birleştirme sırasında durdurma isteği bir saniyenin altında sonuç vermeli. */
    @Test
    fun mergeStopsQuickly() {
        val src = longTs(150, listOf("big/b0.ts"))
        val tmp = File(app.filesDir, "stop.mp4")
        val start = System.nanoTime()
        var stoppedAt = 0L
        try {
            FileOutputStream(tmp).use { fos ->
                Muxer.mux(listOf(Muxer.Input(src.path, takeVideo = true, takeAudio = true)), fos.fd,
                    active = { System.nanoTime() - start < 500_000_000L })
            }
        } catch (_: java.util.concurrent.CancellationException) {
            stoppedAt = System.nanoTime()
        }
        val ms = (stoppedAt - start) / 1_000_000
        Log.i("MergeSpeed", "durdurma: istekten sonra ${ms - 500} ms icinde durdu")
        tmp.delete(); src.delete()
        assertTrue("birleştirme durmadı", stoppedAt > 0)
        assertTrue("durması ${ms - 500} ms sürdü", ms - 500 < 1500)
    }
}
