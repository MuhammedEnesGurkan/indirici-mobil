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
    private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    @Volatile var converterFactory: ((MediaFormat) -> AudioConverter?)? = null

    private fun canDecode(f: MediaFormat): Boolean = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
    } catch (_: Exception) { true }

    /** @property skippedAudio girdilerde olup alınmayan ses izi sayısı; audioEndUs/videoEndUs son örnek zamanları */
    data class Result(val audioTracks: Int, val skippedAudio: Int, val videoEndUs: Long, val audioEndUs: Long, val notes: String = "") {
        /** Ses yok ya da görüntünün yarısından kısa: sessiz/eksik çıktı */
        val audioMissing get() = audioTracks == 0 || (videoEndUs > 10_000_000 && audioEndUs < videoEndUs / 2)
    }

    /**
     * @param durationHintUs kaynağın süresi (liste toplamı); TS dosyalarında kapsayıcı süre vermediği için ilerleme buna göre
     * @param active false dönerse birleştirme durdurulur (duraklat/iptal)
     */
    fun mux(inputs: List<Input>, out: FileDescriptor, durationHintUs: Long = 0, active: () -> Boolean = { true }, onProgress: (Float) -> Unit = {}): Result {
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        class Src(val ex: MediaExtractor, val map: Map<Int, Int>, val audio: Set<Int>, val video: Boolean) { var shift = 0L }
        val converters = HashMap<Int, AudioConverter>()
        val aacTracks = HashSet<Int>() // kaynak iz numarası (girdi başına ayrı Src olduğundan çakışma önemsiz)
        var adtsStripped = 0L
        var adtsMulti = 0L
        val notes = ArrayList<String>()
        val srcs = ArrayList<Src>()
        var hasVideo = false
        var durationUs = 0L
        var skipped = 0
        val videoDst = HashSet<Int>()
        try {
            for ((k, inp) in inputs.withIndex()) {
                if (!active()) throw java.util.concurrent.CancellationException("Birleştirme durduruldu")
                val ex = MediaExtractor()
                ex.setDataSource(inp.path)
                val map = HashMap<Int, Int>()
                val audio = HashSet<Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                    if (mime.startsWith("audio/") && !inp.takeAudio) { skipped++; notes += "girdi$k $mime: alınmadı" }
                    if (mime.startsWith("video/") && inp.takeVideo && !hasVideo) {
                        hasVideo = true
                        map[i] = muxer.addTrack(f).also { videoDst += it }
                        ex.selectTrack(i)
                    } else if (mime.startsWith("audio/") && inp.takeAudio) {
                        val conv = if (!canDecode(f)) try { converterFactory?.invoke(f) } catch (_: Throwable) { null } else null
                        val tf = conv?.outputFormat ?: f
                        // TS'den gelen AAC bazen ADTS başlıklı verilir ("is-adts"); MP4'te başlık olmamalı
                        if (conv == null && mime == MediaFormat.MIMETYPE_AUDIO_AAC) { tf.setInteger("is-adts", 0); aacTracks += i }
                        if (inp.lang.isNotEmpty()) tf.setString(MediaFormat.KEY_LANGUAGE, Lang.toIso3(inp.lang))
                        map[i] = muxer.addTrack(tf).also { d -> if (conv != null) converters[d] = conv }
                        notes += "girdi$k $mime: " + if (conv != null) "AAC'ye çevriliyor" else if (!canDecode(f)) "olduğu gibi (telefon çözemiyor, çevirici yok)" else "olduğu gibi"
                        audio += i
                        ex.selectTrack(i)
                    }
                }
                if (map.isEmpty()) ex.release() else srcs += Src(ex, map, audio, map.keys.any { k -> ex.getTrackFormat(k).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true })
            }
            require(srcs.isNotEmpty()) { "Birleştirilecek iz bulunamadı" }
            if (durationHintUs > 0) durationUs = maxOf(durationUs, durationHintUs)
            muxer.start()
            val buf = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Örnek kaldı mı: sampleTime değil iz numarasına bakılır (AAC ön dolgusu yüzünden ilk ses
            // örneğinin zamanı negatif olabilir; negatif zamanlı kaynak hiç okunmadan atlanıyordu)
            fun has(s: Src) = s.ex.sampleTrackIndex >= 0
            // Saat tabanı: ayrı ses izi bazen başka bir saatle gelir (ör. görüntü 1 sn'de, ses 10 saatte başlar).
            // Ana kaynağa 5 sn'den yakın başlayanlar ortak tabanı paylaşır (senkron korunur), uzak olan kendi
            // başlangıcına hizalanır; yoksa ses saatlerce sonraya yazılıp video sessiz kalıyordu (eklentiyle aynı kural)
            val firsts = srcs.associateWith { if (has(it)) it.ex.sampleTime else Long.MAX_VALUE }
            val mainSrc = srcs.firstOrNull { it.video } ?: srcs.first()
            val mainFirst = firsts[mainSrc]!!.takeIf { it != Long.MAX_VALUE } ?: firsts.values.minOrNull() ?: 0L
            fun sameClock(f: Long) = f != Long.MAX_VALUE && kotlin.math.abs(f - mainFirst) < 5_000_000
            val base = srcs.filter { sameClock(firsts[it]!!) }.minOfOrNull { firsts[it]!! } ?: mainFirst
            for (s in srcs) s.shift = if (sameClock(firsts[s]!!) || firsts[s] == Long.MAX_VALUE) base else firsts[s]!!
            srcs.forEachIndexed { k, s -> notes += "kaynak$k ilk ${"%.2f".format(java.util.Locale.US, (firsts[s]!!.takeIf { it != Long.MAX_VALUE } ?: 0L) / 1e6)} sn, hizalama ${"%.2f".format(java.util.Locale.US, s.shift / 1e6)} sn${if (s.video) " (görüntü)" else ""}" }
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
                for (c in srcs) if (has(c)) { val t = c.ex.sampleTime - c.shift; if (s == null || t < best) { s = c; best = t } }
                if (s == null) break
                if ((++n and 255) == 0 && !active()) throw java.util.concurrent.CancellationException("Birleştirme durduruldu")
                val ex = s.ex
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { ex.advance(); continue }
                val track = ex.sampleTrackIndex
                val dst = s.map[track]
                if (dst != null) {
                    var t = ex.sampleTime - s.shift + offset[dst]
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
                        last[dst] = if (prev == Long.MIN_VALUE) t else maxOf(prev, t)
                    } else {
                        // AAC örneği ADTS başlığıyla geldiyse (0xFFF senkronu): MP4'te başlıksız, tek tek ham çerçeve olmalı.
                        // TS okuyucusu bir örnekte birden çok ADTS çerçevesi verebiliyor; hepsi ayrılıp her biri kendi
                        // zamanıyla yazılır (yalnız ilk başlığı atmak sesi "cıvıltılı" yapıyordu).
                        if (track in s.audio && track in aacTracks && size > 9 && buf.get(0) == 0xFF.toByte() && (buf.get(1).toInt() and 0xF6) == 0xF0) {
                            var pos = 0; var k = 0
                            while (pos + 7 <= size && buf.get(pos) == 0xFF.toByte() && (buf.get(pos + 1).toInt() and 0xF6) == 0xF0) {
                                val hdr = if ((buf.get(pos + 1).toInt() and 0x01) == 1) 7 else 9
                                val len = ((buf.get(pos + 3).toInt() and 0x03) shl 11) or ((buf.get(pos + 4).toInt() and 0xFF) shl 3) or ((buf.get(pos + 5).toInt() and 0xE0) ushr 5)
                                if (len <= hdr || pos + len > size) break
                                val rate = ADTS_RATES.getOrElse((buf.get(pos + 2).toInt() ushr 2) and 0x0F) { 48000 }
                                var ft = t + k * 1024L * 1_000_000L / rate
                                if (ft <= (last[dst].takeIf { it != Long.MIN_VALUE } ?: -1L)) ft = last[dst] + 1
                                info.set(pos + hdr, len - hdr, ft, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                                muxer.writeSampleData(dst, buf, info)
                                last[dst] = ft
                                adtsStripped++; k++; pos += len
                            }
                            if (k == 0) { info.set(0, size, t, flags); muxer.writeSampleData(dst, buf, info); last[dst] = if (prev == Long.MIN_VALUE) t else maxOf(prev, t) }
                            else if (k > 1) adtsMulti++
                        } else {
                            info.set(0, size, t, flags)
                            muxer.writeSampleData(dst, buf, info)
                            last[dst] = if (prev == Long.MIN_VALUE) t else maxOf(prev, t)
                        }
                    }
                    if (durationUs > 0 && t - lastReport > 1_000_000) { lastReport = t; onProgress((t.toFloat() / durationUs).coerceIn(0f, 1f)) }
                }
                ex.advance()
            }
            for ((d, c) in converters) c.finish { b, inf -> muxer.writeSampleData(d, b, inf) }
            muxer.stop()
            var vEnd = 0L; var aEnd = 0L
            for (d in last.indices) if (last[d] != Long.MIN_VALUE) { if (d in videoDst) vEnd = maxOf(vEnd, last[d]) else aEnd = maxOf(aEnd, last[d]) }
            if (adtsStripped > 0) notes += "AAC: $adtsStripped çerçeveden ADTS başlığı atıldı ($adtsMulti örnekte birden çok çerçeve vardı)"
            notes += "yazılan: görüntü sonu ${"%.1f".format(java.util.Locale.US, vEnd / 1e6)} sn, ses sonu ${"%.1f".format(java.util.Locale.US, aEnd / 1e6)} sn"
            return Result(srcs.sumOf { it.audio.size }, skipped, vEnd, aEnd, notes.joinToString("\n"))
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
