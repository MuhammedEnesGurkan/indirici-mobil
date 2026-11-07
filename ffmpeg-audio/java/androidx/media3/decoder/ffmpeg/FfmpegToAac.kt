// FFmpeg ses çözücüsü kütüphanede paket içi (package-private) olduğu için bu dosya onun paketinde durur.
package androidx.media3.decoder.ffmpeg

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import com.movietrack.app.download.Muxer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Telefonun yerleşik çözücüsü olmayan sesi (AC-3, E-AC-3, DTS, MP2…) FFmpeg ile çözüp
 * telefonun kendi kodlayıcısıyla AAC'ye çevirir. Çok kanallı ses (5.1) stereoya indirilir.
 */
@UnstableApi
class FfmpegToAac private constructor(input: MediaFormat, mime: String) : Muxer.AudioConverter {
    private val rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private val inChannels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private val outChannels = if (inChannels >= 2) 2 else 1
    private val decoder = FfmpegAudioDecoder(
        Format.Builder().setSampleMimeType(mime).setSampleRate(rate).setChannelCount(inChannels).build(),
        16, 16, 64 * 1024, false,
    )
    private val encoder: MediaCodec
    override val outputFormat: MediaFormat
    private val info = MediaCodec.BufferInfo()
    private var basePts = -1L
    private var framesFed = 0L
    private var lastOutPts = -1L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels == 2) 192_000 else 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc(rate, outChannels)))
        }
    }

    override fun feed(data: ByteBuffer, size: Int, ptsUs: Long, write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        if (basePts < 0) basePts = ptsUs
        var inBuf: DecoderInputBuffer? = decoder.dequeueInputBuffer()
        while (inBuf == null) { drainDecoder(write); Thread.sleep(1); inBuf = decoder.dequeueInputBuffer() }
        inBuf.ensureSpaceForWrite(size)
        val src = data.duplicate(); src.position(0); src.limit(size)
        inBuf.data!!.put(src)
        inBuf.timeUs = ptsUs
        inBuf.flip()
        decoder.queueInputBuffer(inBuf)
        drainDecoder(write)
    }

    override fun finish(write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        var inBuf: DecoderInputBuffer? = decoder.dequeueInputBuffer()
        while (inBuf == null) { drainDecoder(write); Thread.sleep(1); inBuf = decoder.dequeueInputBuffer() }
        inBuf.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
        decoder.queueInputBuffer(inBuf)
        val deadline = System.currentTimeMillis() + 5000
        while (!decoderDone && System.currentTimeMillis() < deadline) { if (!drainDecoder(write)) Thread.sleep(1) }
        // Kodlayıcıya akış sonu, kalanları yaz
        var idx = encoder.dequeueInputBuffer(10_000)
        while (idx < 0) { drainEncoder(write); idx = encoder.dequeueInputBuffer(10_000) }
        encoder.queueInputBuffer(idx, 0, 0, pts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        var eos = false
        val end = System.currentTimeMillis() + 5000
        while (!eos && System.currentTimeMillis() < end) eos = drainEncoder(write, wait = true)
    }

    override fun release() {
        try { decoder.release() } catch (_: Throwable) {}
        try { encoder.stop() } catch (_: Throwable) {}
        try { encoder.release() } catch (_: Throwable) {}
    }

    private var decoderDone = false

    /** @return bir çıktı işlendiyse true */
    private fun drainDecoder(write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit): Boolean {
        val out = decoder.dequeueOutputBuffer() ?: return false
        try {
            if (out.isEndOfStream) { decoderDone = true; return true }
            val pcm = out.data ?: return true
            if (pcm.remaining() > 0) encodePcm(pcm.order(ByteOrder.nativeOrder()), decoder.channelCount.coerceAtLeast(1), write)
        } finally { out.release() }
        return true
    }

    private fun pts() = basePts + framesFed * 1_000_000L / rate

    /** 16 bit PCM'i stereoya indirip kodlayıcıya verir. */
    private fun encodePcm(pcm: ByteBuffer, ch: Int, write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        val frames = pcm.remaining() / 2 / ch
        val s = pcm.asShortBuffer()
        val mixed = ShortArray(frames * outChannels)
        for (f in 0 until frames) {
            val o = f * ch
            if (outChannels == 1) { mixed[f] = s.get(o); continue }
            var l: Float; var r: Float
            when {
                ch == 1 -> { l = s.get(o).toFloat(); r = l }
                ch == 2 -> { l = s.get(o).toFloat(); r = s.get(o + 1).toFloat() }
                else -> {
                    // FFmpeg sırası: FL FR FC LFE (BL BR | SL SR)
                    val c = s.get(o + 2) * 0.707f
                    val sl = if (ch > 4) s.get(o + 4) * 0.707f else 0f
                    val sr = if (ch > 5) s.get(o + 5) * 0.707f else 0f
                    l = (s.get(o) + c + sl) * 0.6f; r = (s.get(o + 1) + c + sr) * 0.6f
                }
            }
            mixed[f * 2] = l.coerceIn(-32768f, 32767f).toInt().toShort()
            mixed[f * 2 + 1] = r.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        var off = 0
        while (off < frames) {
            val idx = encoder.dequeueInputBuffer(10_000)
            if (idx < 0) { drainEncoder(write); continue }
            val ib = encoder.getInputBuffer(idx)!!
            ib.clear()
            val n = minOf(frames - off, ib.capacity() / 2 / outChannels)
            ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(mixed, off * outChannels, n * outChannels)
            encoder.queueInputBuffer(idx, 0, n * 2 * outChannels, pts(), 0)
            framesFed += n
            off += n
            drainEncoder(write)
        }
    }

    /** @return akış sonu geldiyse true */
    private fun drainEncoder(write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit, wait: Boolean = false): Boolean {
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (idx < 0) continue // biçim değişimi: csd zaten elle verildi
            val ob = encoder.getOutputBuffer(idx)!!
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                if (info.presentationTimeUs <= lastOutPts) info.presentationTimeUs = lastOutPts + 1
                lastOutPts = info.presentationTimeUs
                ob.position(info.offset); ob.limit(info.offset + info.size)
                write(ob, info)
            }
            encoder.releaseOutputBuffer(idx, false)
            if (eos) return true
        }
    }

    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** AAC-LC AudioSpecificConfig (2 bayt) */
        private fun asc(rate: Int, ch: Int): ByteArray {
            val fi = RATES.indexOf(rate).let { if (it < 0) 4 else it }
            return byteArrayOf(((2 shl 3) or (fi shr 1)).toByte(), (((fi and 1) shl 7) or (ch shl 3)).toByte())
        }

        /** Muxer.converterFactory için: FFmpeg bu biçimi çözebiliyorsa çevirici, değilse null. */
        fun create(f: MediaFormat): Muxer.AudioConverter? {
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            if (!mime.startsWith("audio/") || !FfmpegLibrary.isAvailable() || !FfmpegLibrary.supportsFormat(mime)) return null
            if (!f.containsKey(MediaFormat.KEY_SAMPLE_RATE) || !f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) return null
            if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) !in RATES) return null
            return FfmpegToAac(f, mime)
        }
    }
}
