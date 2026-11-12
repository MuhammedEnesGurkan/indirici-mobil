package com.movietrack.app.download

import com.movietrack.app.App
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * Doğrudan dosya indirme: sunucu aralık (Range) destekliyorsa dosya parçalara bölünür ve
 * her parça ayrı bağlantıyla doğrudan dosyadaki yerine yazılır (1DM'deki gibi en fazla 16).
 * Parçaların ilerlemesi kaydedilir; uygulama kapansa bile kaldığı yerden devam eder.
 */
class DirectDownloader(private val app: App, private val ctx: JobContext) {
    private class Part(val start: Long, @Volatile var end: Long, @Volatile var done: Long) {
        val size get() = if (end < 0) -1 else end - start + 1
        val finished get() = end >= 0 && done >= size
    }

    private fun encode(parts: List<Part>) = synchronized(parts) { parts.sortedBy { it.start }.joinToString(";") { "${it.start},${it.end},${it.done}" } }
    private fun decode(s: String) = s.split(';').filter { it.isNotBlank() }.map { val p = it.split(','); Part(p[0].toLong(), p[1].toLong(), p[2].toLong()) }

    suspend fun run() {
        val e = ctx.e
        var parts = decode(e.parts)
        if (parts.isEmpty() || e.outputUri == null) {
            // 1) Yokla: boyut, aralık desteği, dosya adı, tür
            ctx.phase = "Bağlanılıyor"
            ctx.emit()
            var ranges = false
            Http.retry(3) {
                Http.execute(Http.request(e, e.url, longArrayOf(0, 0))).use { res ->
                    val cr = res.header("Content-Range")
                    if (res.code == 206 && cr != null) { ranges = true; e.totalBytes = cr.substringAfter('/').toLongOrNull() ?: e.totalBytes }
                    else e.totalBytes = res.body?.contentLength()?.takeIf { it > 0 } ?: e.totalBytes
                    val cd = res.header("Content-Disposition")
                    contentDispositionName(cd)?.let { if (e.fileName.isBlank() || e.fileName == Format.fileNameFromUrl(e.url)) e.fileName = Format.safeName(it) }
                    res.header("Content-Type")?.substringBefore(';')?.trim()?.let { if (e.mime.isBlank() && it != "application/octet-stream") e.mime = it }
                }
            }
            e.category = Category.of(e.fileName, e.mime)
            val out = OutputStore.create(app, e.fileName, e.mime, com.movietrack.app.util.KV.decode(e.meta)["folder"]?.takeIf { it.isNotBlank() } ?: e.category)
            e.outputUri = out.encode()
            val total = e.totalBytes
            val n = app.settings.parts
            parts = if (ranges && total > 1_000_000 && n > 1) {
                val chunk = (total + n - 1) / n
                (0 until n).map { i -> Part(i * chunk, minOf(total, (i + 1) * chunk) - 1, 0) }.filter { it.start <= it.end }
            } else listOf(Part(0, if (total > 0 && ranges) total - 1 else -1, 0))
            e.parts = encode(parts)
            e.downloadedBytes = 0
            ctx.saveNow()
        } else {
            e.downloadedBytes = parts.sumOf { it.done }
        }
        ctx.partsProvider = { encode(parts) }
        ctx.phase = "İndiriliyor"
        val out = OutputFile.decode(e.outputUri)!!
        out.openRw(app).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { ch ->
                if (parts.size == 1 && parts[0].end < 0) {
                    // Aralık desteklenmiyor: baştan tek bağlantıyla (devam ettirilemez)
                    parts[0].done = 0
                    e.downloadedBytes = 0
                    streamPart(ch, parts[0])
                    e.totalBytes = parts[0].done
                } else coroutineScope {
                    // Dinamik bölme (IDM'deki gibi): boşalan bağlantı, kalan en büyük parçayı ikiye bölüp yarısını
                    // devralır. Böylece hızlı parçalar bitince bağlantılar boşta kalmaz, yavaş kalan parça
                    // tek bağlantıyla sürünmez.
                    val live = java.util.Collections.synchronizedList(parts.toMutableList())
                    parts = live
                    val claimed = HashSet<Part>()
                    fun next(): Part? = synchronized(live) {
                        live.firstOrNull { !it.finished && it !in claimed }?.let { claimed += it; return@synchronized it }
                        val victim = live.filter { it in claimed && !it.finished }.maxByOrNull { it.end - (it.start + it.done) } ?: return@synchronized null
                        val pos = victim.start + victim.done
                        val remaining = victim.end - pos + 1
                        // Yarısı, sürmekte olan tek okumadan (128 KB) çok büyük olmalı ki bölme noktası aşılmasın
                        if (remaining < MIN_SPLIT) return@synchronized null
                        val mid = pos + remaining / 2
                        val np = Part(mid, victim.end, 0)
                        victim.end = mid - 1
                        live += np; claimed += np
                        np
                    }
                    (0 until app.settings.parts.coerceIn(1, 16)).map {
                        async { while (true) { val p = next() ?: break; Http.retry(8) { streamPart(ch, p) } } }
                    }.awaitAll()
                }
                ch.force(true)
            }
        }
        e.parts = encode(parts)
        out.publish(app)
    }

    private companion object { const val MIN_SPLIT = 1L shl 20 }

    private suspend fun streamPart(ch: FileChannel, p: Part) {
        val e = ctx.e
        val from = p.start + p.done
        val range = if (p.end < 0 && p.start == 0L && p.done == 0L) null else longArrayOf(from, p.end)
        Http.execute(Http.request(e, e.url, range)).use { res ->
            if (range != null && range[0] > 0 && res.code != 206) throw java.io.IOException("Sunucu kaldığı yerden devam etmeyi reddetti")
            val input = res.body!!.byteStream()
            val buf = ByteArray(128 * 1024)
            var pos = from
            while (true) {
                coroutineContext.ensureActive()
                val limit = if (p.end >= 0) minOf(buf.size.toLong(), p.end - pos + 1).toInt() else buf.size
                if (limit <= 0) break
                val n = input.read(buf, 0, limit)
                if (n < 0) break
                val bb = ByteBuffer.wrap(buf, 0, n)
                var written = 0
                while (bb.hasRemaining()) written += ch.write(bb, pos + written)
                pos += n
                p.done += n
                ctx.onBytes(n)
            }
            if (p.end >= 0 && !p.finished) throw java.io.IOException("Bağlantı erken kapandı")
        }
    }

    private fun contentDispositionName(cd: String?): String? {
        if (cd == null) return null
        Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let { return try { URLDecoder.decode(it.groupValues[1].trim('"'), "UTF-8") } catch (_: Exception) { null } }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)?.trim()
    }
}
