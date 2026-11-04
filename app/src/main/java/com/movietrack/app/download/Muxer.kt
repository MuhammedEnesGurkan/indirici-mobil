package com.movietrack.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.movietrack.app.util.Lang
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * TS / parçalı MP4 akışlarını yeniden kodlamadan tek MP4 dosyasına birleştirir (Android'in kendi
 * MediaExtractor + MediaMuxer'ı). Video + birden çok ses izi; ses izlerine dil etiketi (aud:xx) yazılır.
 */
object Muxer {
    data class Input(val path: String, val takeVideo: Boolean, val takeAudio: Boolean, val lang: String = "")

    /** @return eklenen ses izi sayısı */
    fun mux(inputs: List<Input>, out: FileDescriptor, onProgress: (Float) -> Unit = {}): Int {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        class Src(val ex: MediaExtractor, val map: Map<Int, Int>, val audio: Set<Int>)
        val srcs = ArrayList<Src>()
        var hasVideo = false
        var durationUs = 0L
        try {
            for (inp in inputs) {
                val ex = MediaExtractor()
                ex.setDataSource(inp.path)
                val map = HashMap<Int, Int>()
                val audio = HashSet<Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                    if (mime.startsWith("video/") && inp.takeVideo && !hasVideo) {
                        hasVideo = true
                        map[i] = muxer.addTrack(f)
                        ex.selectTrack(i)
                    } else if (mime.startsWith("audio/") && inp.takeAudio) {
                        if (inp.lang.isNotEmpty()) f.setString(MediaFormat.KEY_LANGUAGE, Lang.toIso3(inp.lang))
                        map[i] = muxer.addTrack(f)
                        audio += i
                        ex.selectTrack(i)
                    }
                }
                if (map.isEmpty()) ex.release() else srcs += Src(ex, map, audio)
            }
            require(srcs.isNotEmpty()) { "Birleştirilecek iz bulunamadı" }
            muxer.start()
            val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Örnek kaldı mı: sampleTime değil iz numarasına bakılır (AAC ön dolgusu yüzünden ilk ses
            // örneğinin zamanı negatif olabilir; negatif zamanlı kaynak hiç okunmadan atlanıyordu)
            fun has(s: Src) = s.ex.sampleTrackIndex >= 0
            val base = srcs.filter(::has).minOfOrNull { it.ex.sampleTime } ?: 0L
            val last = HashMap<Int, Long>()
            val offset = HashMap<Int, Long>()
            var lastReport = 0L
            while (true) {
                // Zamanı en küçük örneği seç (izleri iç içe yaz)
                val s = srcs.filter(::has).minByOrNull { it.ex.sampleTime } ?: break
                val ex = s.ex
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { ex.advance(); continue }
                val track = ex.sampleTrackIndex
                val dst = s.map[track]
                if (dst != null) {
                    var t = ex.sampleTime - base + (offset[dst] ?: 0)
                    val prev = last[dst]
                    // Kesintide zaman sıfırlanırsa (reklam/periyot) kaydır
                    if (prev != null && t < prev - 1_000_000) {
                        val add = prev - t + 20_000
                        offset[dst] = (offset[dst] ?: 0) + add
                        t += add
                    }
                    if (track in s.audio && prev != null && t <= prev) t = prev + 1
                    if (t < 0) t = 0
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, t, flags)
                    muxer.writeSampleData(dst, buf, info)
                    last[dst] = maxOf(prev ?: 0, t)
                    if (durationUs > 0 && t - lastReport > 2_000_000) { lastReport = t; onProgress((t.toFloat() / durationUs).coerceIn(0f, 1f)) }
                }
                ex.advance()
            }
            muxer.stop()
            return srcs.sumOf { it.audio.size }
        } finally {
            srcs.forEach { it.ex.release() }
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}

/** WebVTT → SRT (HLS altyazı segmentleri birleştirilir, tekrarlar atılır). */
object Subtitles {
    data class Cue(val start: Double, val end: Double, val text: String)

    private fun clock(s: String): Double {
        val m = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})").find(s) ?: return -1.0
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble() + m.groupValues[4].padEnd(3, '0').toDouble() / 1000
    }

    /** @return ipuçları ve X-TIMESTAMP-MAP'teki MPEGTS değeri (yoksa null) */
    fun parseVtt(text: String): Pair<List<Cue>, Long?> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val cues = ArrayList<Cue>()
        var mpegts: Long? = null
        var local = 0.0
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("X-TIMESTAMP-MAP")) {
                mpegts = Regex("MPEGTS:(\\d+)").find(l)?.groupValues?.get(1)?.toLong()
                local = Regex("LOCAL:([\\d:.]+)").find(l)?.groupValues?.get(1)?.let { clock(it) } ?: 0.0
            }
            if (l.contains("-->")) {
                val a = clock(l.substringBefore("-->"))
                val b = clock(l.substringAfter("-->").trim().split(' ')[0])
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { if (body.isNotEmpty()) body.append('\n'); body.append(lines[i]); i++ }
                if (a >= 0 && b > a && body.isNotEmpty()) cues += Cue(a - local, b - local, body.toString())
            }
            i++
        }
        return cues to mpegts
    }

    fun toSrt(cues: List<Cue>): String {
        fun f(t: Double): String {
            val ms = (maxOf(0.0, t) * 1000).toLong()
            return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
        }
        val clean = cues.sortedBy { it.start }.distinctBy { "${(it.start * 100).toLong()}|${it.text}" }
        return clean.mapIndexed { i, c -> "${i + 1}\n${f(c.start)} --> ${f(c.end)}\n${c.text.replace(Regex("<(?!/?[ibu]>)[^>]+>"), "")}\n" }.joinToString("\n")
    }
}
