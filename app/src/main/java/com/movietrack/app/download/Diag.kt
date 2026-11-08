package com.movietrack.app.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Tanı raporu yardımcıları: dosyadaki izler ve sesin gerçekten duyulabilir olup olmadığı. */
object Diag {
    private fun sec(us: Long) = "%.1f sn".format(java.util.Locale.US, us / 1e6)

    /** Her iz: biçim, örnekleme, kanal, süre ve ilk örneğin zamanı. */
    fun tracks(open: (MediaExtractor) -> Unit): String {
        val ex = MediaExtractor()
        return try {
            open(ex)
            (0 until ex.trackCount).joinToString("; ") { i ->
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
                val parts = arrayListOf(mime)
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) parts += "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz"
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) parts += "${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} kanal"
                if (f.containsKey(MediaFormat.KEY_DURATION)) parts += "süre ${sec(f.getLong(MediaFormat.KEY_DURATION))}"
                f.getString(MediaFormat.KEY_LANGUAGE)?.let { parts += "dil $it" }
                val ok = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null } catch (_: Exception) { true }
                if (!ok) parts += "TELEFONDA ÇÖZÜCÜ YOK"
                // İlk örneğin zamanı (ses görüntüden çok sonra başlıyorsa burada görünür)
                ex.selectTrack(i); ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (ex.sampleTrackIndex >= 0) parts += "ilk örnek ${sec(ex.sampleTime)}"
                ex.unselectTrack(i)
                parts.joinToString(", ")
            }.ifEmpty { "iz yok" }
        } catch (t: Throwable) { "okunamadı: ${t.message}" } finally { ex.release() }
    }

    /**
     * Çıktının ilk ses izinin ilk ~20 saniyesini çözüp seviyeyi ölçer.
     * "iz var ama sessiz" (sıfır veri), "çözücü yok" ve "örnek yok" durumlarını ayırır.
     */
    fun audioLevel(ctx: Context, uri: Uri): String {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return "ses izi yok"
            val f = ex.getTrackFormat(ti)
            ex.selectTrack(ti)
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) ?: return "ses çözücüsü yok (${f.getString(MediaFormat.KEY_MIME)})"
            codec = MediaCodec.createByCodecName(name).apply { configure(f, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inDone = false; var samples = 0L; var sumSq = 0.0; var peak = 0; var firstPts = -1L; var inCount = 0
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline && samples < 48000L * 2 * 20) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { if (firstPts < 0) firstPts = ex.sampleTime; codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance(); inCount++ }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.asShortBuffer()
                    while (sb.hasRemaining()) { val v = sb.get().toInt(); sumSq += v.toDouble() * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v); samples++ }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (inCount == 0) return "ses izinde örnek yok"
            if (samples == 0L) return "ses çözülemedi ($inCount örnek verildi, çıktı yok)"
            val rms = sqrt(sumSq / samples)
            val db = if (rms < 1) "-∞" else "%.0f".format(20 * log10(rms / 32768))
            "ilk ${sec(firstPts.coerceAtLeast(0))}'den itibaren ${samples / 2 / 48} ms kadar çözüldü, seviye $db dBFS, tepe $peak" +
                if (peak < 30) " → SESSİZ (veri sıfır)" else " → ses VAR"
        } catch (t: Throwable) { "ölçülemedi: ${t.message}" } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }
}
