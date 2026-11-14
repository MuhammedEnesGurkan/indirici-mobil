package com.movietrack.app.download

import android.content.Context
import android.net.Uri
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.util.KV
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * indirici'nin indirdiği video/ses dosyalarına küçük bir işaret yazar ve okur.
 *
 * İşaret dosyanın sonuna, kapsayıcının yok saydığı bir alan olarak eklenir (birkaç yüz bayt):
 *  - MP4/M4A/MOV: üst düzey `free` kutusu (tüm oynatıcılar atlar)
 *  - MKV/WebM: EBML `Void` öğesi
 *  - MPEG-TS: boş (PID 0x1FFF) TS paketleri
 * Diğer türlere (zip, apk, mp3, pdf…) dokunulmaz: sona bayt eklemek onları bozabilir.
 *
 * İçinde kaynak bağlantı, sayfa, başlık ve etiketler durur. Uygulama silinip yeniden kurulunca
 * tarama bu sayede (1) dosyanın indirici'den geldiğini anlar, başka klasöre taşınmış olsa da
 * bulur, (2) bağlantıyı ve başlığı geri getirir ("Tekrar indir" yeniden çalışır).
 */
object FileTag {
    private const val MAGIC = "INDIRICI-TAG1\n"
    private const val END = "\nINDIRICI-END"
    private const val TAIL = 8192
    private const val MAX_PAYLOAD = 3000

    private enum class Kind { MP4, EBML, TS }

    /** Etikette saklanan bilgiler. */
    data class Info(val url: String, val pageUrl: String?, val title: String?, val labels: String, val createdAt: Long)

    /** İndirme bitince çağrılır; desteklenmeyen türde ya da hata olursa sessizce hiçbir şey yapmaz. */
    fun write(ctx: Context, uri: Uri, e: DownloadEntity) {
        try {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                append(FileInputStream(pfd.fileDescriptor).channel, FileOutputStream(pfd.fileDescriptor).channel, e)
            }
        } catch (_: Throwable) {
        }
    }

    /** İşareti dosya sonuna ekler; eklendiyse true. (Birim testleri için kanal üzerinden.) */
    internal fun append(rd: java.nio.channels.FileChannel, wr: java.nio.channels.FileChannel, e: DownloadEntity): Boolean {
        val size = rd.size()
        if (size < 1024) return false
        val head = ByteBuffer.allocate(4 * 188)
        rd.read(head, 0)
        val kind = sniff(head.array(), head.position()) ?: return false
        if (readTail(rd, size) != null) return false // zaten işaretli
        if (kind == Kind.MP4 && !mp4EndsCleanly(rd, size)) return false
        val payload = payloadOf(e)
        val bytes = when (kind) {
            Kind.MP4 -> mp4Box(payload)
            Kind.EBML -> ebmlVoid(payload)
            Kind.TS -> tsPackets(payload)
        }
        wr.write(ByteBuffer.wrap(bytes), size)
        wr.force(true)
        return true
    }

    /** Dosyada indirici işareti varsa içeriğini döner. */
    fun read(ctx: Context, uri: Uri): Info? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            readTail(ch, ch.size())
        }
    } catch (_: Throwable) { null }

    /** Bu dosya türüne işaret yazılabilir mi (taramada hangi dosyaların okunacağını seçmek için). */
    fun taggable(name: String): Boolean =
        com.movietrack.app.util.Format.extOf(name) in setOf("mp4", "m4v", "m4a", "mov", "mkv", "mka", "webm", "ts")

    private fun payloadOf(e: DownloadEntity): ByteArray {
        val m = linkedMapOf("url" to e.url, "page" to (e.pageUrl ?: ""), "title" to (e.title ?: ""), "labels" to e.labels, "at" to e.createdAt.toString())
        var kv = KV.encode(m)
        // Çok uzun imzalı adresler kırpılır: işaret küçük kalsın
        if (kv.length > MAX_PAYLOAD) kv = KV.encode(m + ("url" to e.url.take(1500)) + ("page" to (e.pageUrl ?: "").take(600)))
        return (MAGIC + kv.take(MAX_PAYLOAD) + END).toByteArray(Charsets.UTF_8)
    }

    private fun sniff(b: ByteArray, n: Int): Kind? = when {
        n >= 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte() -> Kind.MP4
        n >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte() -> Kind.EBML
        n >= 377 && b[0] == 0x47.toByte() && b[188] == 0x47.toByte() && b[376] == 0x47.toByte() -> Kind.TS
        else -> null
    }

    /** MP4'ün üst düzey kutuları dosya sonunda tam bitiyor mu (son kutu "dosya sonuna kadar" ise ekleme yapılmaz). */
    private fun mp4EndsCleanly(ch: java.nio.channels.FileChannel, size: Long): Boolean {
        var pos = 0L
        val h = ByteBuffer.allocate(16)
        var guard = 0
        while (pos < size && guard++ < 100_000) {
            h.clear(); if (ch.read(h, pos) < 8) return false
            h.flip()
            var len = h.getInt(0).toLong() and 0xFFFFFFFFL
            if (len == 1L) { if (h.limit() < 16) return false; len = h.getLong(8) }
            if (len == 0L || len < 8) return false
            pos += len
        }
        return pos == size
    }

    private fun mp4Box(p: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + p.size)
        b.putInt(8 + p.size); b.put("free".toByteArray(Charsets.US_ASCII)); b.put(p)
        return b.array()
    }

    private fun ebmlVoid(p: ByteArray): ByteArray {
        // ID 0xEC, 8 baytlık boyut alanı (0x01 + 7 bayt)
        val b = ByteBuffer.allocate(1 + 8 + p.size)
        b.put(0xEC.toByte()); b.put(0x01.toByte())
        val len = p.size.toLong()
        for (i in 6 downTo 0) b.put(((len shr (8 * i)) and 0xFF).toByte())
        b.put(p)
        return b.array()
    }

    private fun tsPackets(p: ByteArray): ByteArray {
        val chunk = 184
        val count = (p.size + chunk - 1) / chunk
        val out = ByteBuffer.allocate(count * 188)
        for (i in 0 until count) {
            out.put(0x47.toByte()); out.put(0x1F.toByte()); out.put(0xFF.toByte()); out.put((0x10 or (i and 0x0F)).toByte())
            val part = p.copyOfRange(i * chunk, minOf(p.size, (i + 1) * chunk))
            out.put(part)
            repeat(chunk - part.size) { out.put(0xFF.toByte()) }
        }
        return out.array()
    }

    internal fun readTail(ch: java.nio.channels.FileChannel, size: Long = ch.size()): Info? {
        if (size <= 0) return null
        val n = minOf(size, TAIL.toLong()).toInt()
        val buf = ByteBuffer.allocate(n)
        ch.read(buf, size - n)
        val raw = buf.array()
        val candidates = ArrayList<ByteArray>()
        // TS: işaret 184 baytlık parçalara bölünmüştür; boş paketlerin yüklerini birleştir
        if (size % 188L == 0L) {
            val joined = java.io.ByteArrayOutputStream()
            var off = n % 188
            while (off + 188 <= n) {
                if (raw[off] == 0x47.toByte() && raw[off + 1] == 0x1F.toByte() && raw[off + 2] == 0xFF.toByte()) joined.write(raw, off + 4, 184)
                off += 188
            }
            if (joined.size() > 0) candidates += joined.toByteArray()
        }
        candidates += raw
        for (bytes in candidates) {
            val s = String(bytes, Charsets.ISO_8859_1)
            val a = s.lastIndexOf(MAGIC)
            if (a < 0) continue
            val b = s.indexOf(END, a)
            if (b < 0) continue
            val m = KV.decode(String(bytes, a + MAGIC.length, b - a - MAGIC.length, Charsets.UTF_8))
            return Info(m["url"] ?: "", m["page"]?.ifEmpty { null }, m["title"]?.ifEmpty { null }, m["labels"] ?: "", m["at"]?.toLongOrNull() ?: 0L)
        }
        return null
    }
}
