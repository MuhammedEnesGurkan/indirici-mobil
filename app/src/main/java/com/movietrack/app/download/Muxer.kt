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

    /** Telefonun çözemediği sesi (AC-3, E-AC-3, DTS, MP2…) her yerde çalan AAC'ye çevirir. */
    interface AudioConverter {
        /** Muxer'a eklenecek AAC biçimi (csd-0 dahil) */
        val outputFormat: MediaFormat
        fun feed(data: ByteBuffer, size: Int, ptsUs: Long, write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit)
        fun finish(write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit)
        fun release()
    }

    /** Uygulama sağlarsa (indirici: FFmpeg) çözülemeyen ses çevrilir; yoksa olduğu gibi kopyalanır. */
    @Volatile var converterFactory: ((MediaFormat) -> AudioConverter?)? = null

    private fun canDecode(f: MediaFormat): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
    } catch (_: Exception) { true }

    /** @property skippedAudio girdilerde olup alınmayan ses izi sayısı; audioEndUs/videoEndUs son örnek zamanları */
    data class Result(val audioTracks: Int, val skippedAudio: Int, val videoEndUs: Long, val audioEndUs: Long) {
        /** Ses yok ya da görüntünün yarısından kısa: sessiz/eksik çıktı */
        val audioMissing get() = audioTracks == 0 || (videoEndUs > 10_000_000 && audioEndUs < videoEndUs / 2)
    }

    /**
     * @param durationHintUs kaynağın süresi (liste toplamı); TS dosyalarında kapsayıcı süre vermediği için ilerleme buna göre
     * @param active false dönerse birleştirme durdurulur (duraklat/iptal)
     */
    fun mux(inputs: List<Input>, out: FileDescriptor, durationHintUs: Long = 0, active: () -> Boolean = { true }, onProgress: (Float) -> Unit = {}): Result {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        class Src(val ex: MediaExtractor, val map: Map<Int, Int>, val audio: Set<Int>)
        val converters = HashMap<Int, AudioConverter>()
        val srcs = ArrayList<Src>()
        var hasVideo = false
        var durationUs = 0L
        var skipped = 0
        val videoDst = HashSet<Int>()
        try {
            for (inp in inputs) {
                if (!active()) throw java.util.concurrent.CancellationException("Birleştirme durduruldu")
                val ex = MediaExtractor()
                ex.setDataSource(inp.path)
                val map = HashMap<Int, Int>()
                val audio = HashSet<Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                    if (mime.startsWith("audio/") && !inp.takeAudio) skipped++
                    if (mime.startsWith("video/") && inp.takeVideo && !hasVideo) {
                        hasVideo = true
                        map[i] = muxer.addTrack(f).also { videoDst += it }
                        ex.selectTrack(i)
                    } else if (mime.startsWith("audio/") && inp.takeAudio) {
                        val conv = if (!canDecode(f)) try { converterFactory?.invoke(f) } catch (_: Throwable) { null } else null
                        val tf = conv?.outputFormat ?: f
                        if (inp.lang.isNotEmpty()) tf.setString(MediaFormat.KEY_LANGUAGE, Lang.toIso3(inp.lang))
                        map[i] = muxer.addTrack(tf).also { d -> if (conv != null) converters[d] = conv }
                        audio += i
                        ex.selectTrack(i)
                    }
                }
                if (map.isEmpty()) ex.release() else srcs += Src(ex, map, audio)
            }
            require(srcs.isNotEmpty()) { "Birleştirilecek iz bulunamadı" }
            if (durationHintUs > 0) durationUs = maxOf(durationUs, durationHintUs)
            muxer.start()
            val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Örnek kaldı mı: sampleTime değil iz numarasına bakılır (AAC ön dolgusu yüzünden ilk ses
            // örneğinin zamanı negatif olabilir; negatif zamanlı kaynak hiç okunmadan atlanıyordu)
            fun has(s: Src) = s.ex.sampleTrackIndex >= 0
            var base = Long.MAX_VALUE
            for (s in srcs) if (has(s)) base = minOf(base, s.ex.sampleTime)
            if (base == Long.MAX_VALUE) base = 0L
            // Hedef iz başına son zaman ve kaydırma (dizi: HashMap kutulamasından kaçınılır)
            val nTracks = srcs.sumOf { it.map.size }
            val last = LongArray(nTracks) { Long.MIN_VALUE }
            val offset = LongArray(nTracks)
            var lastReport = 0L
            var n = 0
            while (true) {
                // Zamanı en küçük örneği seç (izleri iç içe yaz); tek kaynakta doğrudan o
                var s: Src? = null
                var best = Long.MAX_VALUE
                for (c in srcs) if (has(c)) { val t = c.ex.sampleTime; if (s == null || t < best) { s = c; best = t } }
                if (s == null) break
                if ((++n and 255) == 0 && !active()) throw java.util.concurrent.CancellationException("Birleştirme durduruldu")
                val ex = s.ex
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { ex.advance(); continue }
                val track = ex.sampleTrackIndex
                val dst = s.map[track]
                if (dst != null) {
                    var t = ex.sampleTime - base + offset[dst]
                    val prev = last[dst]
                    // Kesintide zaman sıfırlanırsa (reklam/periyot) kaydır
                    if (prev != Long.MIN_VALUE && t < prev - 1_000_000) {
                        val add = prev - t + 20_000
                        offset[dst] += add
                        t += add
                    }
                    if (track in s.audio && prev != Long.MIN_VALUE && t <= prev) t = prev + 1
                    if (t < 0) t = 0
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    val conv = converters[dst]
                    if (conv != null) {
                        conv.feed(buf, size, t) { b, inf -> muxer.writeSampleData(dst, b, inf) }
                    } else {
                        info.set(0, size, t, flags)
                        muxer.writeSampleData(dst, buf, info)
                    }
                    last[dst] = if (prev == Long.MIN_VALUE) t else maxOf(prev, t)
                    if (durationUs > 0 && t - lastReport > 1_000_000) { lastReport = t; onProgress((t.toFloat() / durationUs).coerceIn(0f, 1f)) }
                }
                ex.advance()
            }
            for ((d, c) in converters) c.finish { b, inf -> muxer.writeSampleData(d, b, inf) }
            muxer.stop()
            var vEnd = 0L; var aEnd = 0L
            for (d in last.indices) if (last[d] != Long.MIN_VALUE) { if (d in videoDst) vEnd = maxOf(vEnd, last[d]) else aEnd = maxOf(aEnd, last[d]) }
            return Result(srcs.sumOf { it.audio.size }, skipped, vEnd, aEnd)
        } finally {
            srcs.forEach { it.ex.release() }
            converters.values.forEach { try { it.release() } catch (_: Throwable) {} }
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
