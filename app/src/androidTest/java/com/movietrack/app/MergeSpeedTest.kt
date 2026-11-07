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

    private fun longTs(repeats: Int): File {
        val segs = listOf("s0.ts", "s1.ts", "s2.ts").map { n -> assets.open("fx/ts/$n").use { it.readBytes() } }
        val f = File(app.cacheDir, "long.ts")
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
        val tmp = File(app.cacheDir, "tmp.mp4")
        FileOutputStream(tmp).use { fos -> Muxer.mux(inputs, fos.fd) }
        val muxOnly = (System.nanoTime() - t0) / 1_000_000
        val outB = OutputStore.create(app, "hiz-b-${System.currentTimeMillis()}.mp4", "video/mp4", "test")
        outB.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { os -> FileInputStream(tmp).use { it.copyTo(os, 4 shl 20) } } }
        outB.publish(app)
        val b = (System.nanoTime() - t0) / 1_000_000
        outB.delete(app)

        Log.i("MergeSpeed", "kaynak=${src.length() / 1024} KB mp4=${tmp.length() / 1024} KB A_dogrudan=${a} ms B_ozel+kopya=${b} ms (yalniz birlestirme=${muxOnly} ms)")
        tmp.delete(); src.delete()
        assertTrue(a > 0 && b > 0)
    }
}
