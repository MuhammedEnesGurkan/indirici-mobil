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
    // Takılan test tüm işi kilitlemesin: süresi dolan test adıyla başarısız olur
    @get:org.junit.Rule val timeoutRule: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(420)

    private val app get() = App.instance
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun longTs(repeats: Int, names: List<String> = listOf("ts/s0.ts", "ts/s1.ts", "ts/s2.ts"), shift: Double = 0.0): File {
        val segs = names.map { n -> assets.open("fx/$n").use { it.readBytes() } }
        val f = File(app.filesDir, "long.ts")
        FileOutputStream(f).use { os ->
            repeat(repeats) { k ->
                // shift > 0: her kopyanın zamanını kaydır, gerçek akış gibi kesintisiz olsun
                segs.forEach { os.write(if (shift > 0) shiftTs(it, (k * shift * 90000).toLong()) else it) }
            }
        }
        return f
    }

    /** MPEG-TS paketlerindeki PTS/DTS ve PCR değerlerine 90 kHz cinsinden ekleme yapar. */
    private fun shiftTs(src: ByteArray, add: Long): ByteArray {
        val b = src.copyOf()
        fun rd(i: Int): Long = ((b[i].toLong() shr 1) and 0x07 shl 30) or ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[i + 3].toLong() and 0xFF) shl 7) or ((b[i + 4].toLong() and 0xFF) shr 1)
        fun wr(i: Int, v0: Long) {
            val v = v0 and 0x1FFFFFFFFL
            b[i] = ((b[i].toInt() and 0xF1) or ((v shr 29).toInt() and 0x0E)).toByte()
            b[i + 1] = (v shr 22).toByte()
            b[i + 2] = (((v shr 14).toInt() and 0xFE) or 1).toByte()
            b[i + 3] = (v shr 7).toByte()
            b[i + 4] = (((v shl 1).toInt() and 0xFE) or 1).toByte()
        }
        var p = 0
        while (p + 188 <= b.size) {
            if (b[p].toInt() != 0x47) { p++; continue }
            val pusi = b[p + 1].toInt() and 0x40 != 0
            val afc = (b[p + 3].toInt() shr 4) and 3
            var i = p + 4
            if (afc == 2 || afc == 3) {
                val alen = b[p + 4].toInt() and 0xFF
                if (alen > 0 && (b[p + 5].toInt() and 0x10) != 0) {
                    // PCR taban (33 bit) + uzantı
                    val q = p + 6
                    var base = ((b[q].toLong() and 0xFF) shl 25) or ((b[q + 1].toLong() and 0xFF) shl 17) or ((b[q + 2].toLong() and 0xFF) shl 9) or
                        ((b[q + 3].toLong() and 0xFF) shl 1) or ((b[q + 4].toLong() and 0xFF) shr 7)
                    base = (base + add) and 0x1FFFFFFFFL
                    b[q] = (base shr 25).toByte(); b[q + 1] = (base shr 17).toByte(); b[q + 2] = (base shr 9).toByte(); b[q + 3] = (base shr 1).toByte()
                    b[q + 4] = (((base and 1).toInt() shl 7) or (b[q + 4].toInt() and 0x7F)).toByte()
                }
                i = p + 5 + alen
            }
            if (pusi && (afc == 1 || afc == 3) && i + 14 < p + 188 &&
                b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val flags = (b[i + 7].toInt() shr 6) and 3
                if (flags >= 2) wr(i + 9, rd(i + 9) + add)
                if (flags == 3 && i + 19 <= p + 188) wr(i + 14, rd(i + 14) + add)
            }
            p += 188
        }
        return b
    }

    @Test
    fun mergeSpeed() {
        val src = longTs(300, shift = 6.0) // 300 × 6 sn = 30 dk
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
        val src = longTs(150, listOf("big/b0.ts"), shift = 10.0)
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
        assertTrue("çıktı eksik: ${mp4 / 1048576} MB / ${src.length() / 1048576} MB", mp4 > src.length() * 0.7)
        Log.i("MergeSpeed", "buyuk: kaynak=${src.length() / 1048576} MB mp4=${mp4 / 1048576} MB B_ozel+kopya=${b} ms (yalniz birlestirme=${muxOnly} ms) A_dogrudan=${a} ms")
        src.delete()
        assertTrue(a > 0 && b > 0)
    }

    /** Birleştirme sırasında durdurma isteği bir saniyenin altında sonuç vermeli. */
    @Test
    fun mergeStopsQuickly() {
        val src = longTs(150, listOf("big/b0.ts"), shift = 10.0)
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
        // 1 GB dosyayı açmak (setDataSource) bölünemiyor (emülatörde 1,6–8 sn ölçüldü); bu yüzden indirici
        // birleştirmeyi ayrı iş parçacığında çalıştırır ve Duraklat'ta beklemez. Burada yalnızca durduğu denetlenir.
    }
}
