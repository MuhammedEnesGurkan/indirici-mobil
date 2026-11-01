package com.movietrack.app.download

import com.movietrack.app.App
import com.movietrack.app.util.Category
import com.movietrack.app.util.KV
import com.movietrack.app.util.Lang
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

/**
 * HLS (m3u8) ve DASH (mpd) indirme:
 *  - seçilen kalite + seçilen ses dilleri (aud:xx) + altyazılar (subtitle:xx)
 *  - segmentler paralel indirilir, AES-128 çözülür, reklam araları atlanabilir
 *  - indirilen segmentler diskte kalır: duraklat/devam ve uygulama yeniden açılınca kaldığı yerden
 *  - sonunda yeniden kodlamadan MP4'e birleştirilir (TS → MP4) ya da ayrı dosyalar olarak kaydedilir
 */
class StreamDownloader(private val app: App, private val ctx: JobContext) {
    private class Plan(val role: String, val lang: String, val name: String, val segments: List<Hls.Segment>, val fmp4: Boolean)

    private val e get() = ctx.e
    private val meta by lazy { KV.decode(e.meta) }
    private val dir by lazy { File(app.filesDir, "streams/${e.id}").apply { mkdirs() } }
    private val keyCache = HashMap<String, ByteArray>()

    suspend fun run() {
        ctx.phase = "Liste alınıyor"
        ctx.emit()
        val skipAds = meta["skipAds"] != "0"
        val plans = if (e.type == "dash") planDash() else planHls()
        val tasks = plans.flatMapIndexed { si, p -> p.segments.mapIndexedNotNull { i, s -> if (skipAds && s.isAd) null else Triple(si, i, s) } }
        ctx.segTotal = tasks.size
        ctx.segDone = tasks.count { segFile(it.first, it.second).exists() }
        e.downloadedBytes = tasks.sumOf { segFile(it.first, it.second).let { f -> if (f.exists()) f.length() else 0L } }
        ctx.phase = "İndiriliyor"

        // 1) Segmentler (paralel)
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(app.settings.parts.coerceIn(2, 16)) {
                launch {
                    while (true) {
                        val k = next.getAndIncrement()
                        if (k >= tasks.size) break
                        val (si, i, seg) = tasks[k]
                        val f = segFile(si, i)
                        if (f.exists()) continue
                        var data = Http.bytes(e, seg.url, seg.range) { ctx.onBytes(it) }
                        data = Hls.stripJunk(decrypt(seg, data))
                        val tmp = File(f.path + ".part")
                        tmp.writeBytes(data)
                        tmp.renameTo(f)
                        ctx.segDone++
                        if (ctx.segDone > 2) e.totalBytes = e.downloadedBytes * ctx.segTotal / ctx.segDone
                    }
                }
            }
        }
        // Init segmentleri (fMP4)
        for ((si, p) in plans.withIndex()) for (m in p.segments.mapNotNull { it.map }.distinct()) {
            val f = initFile(si, m)
            if (!f.exists()) { val seg = p.segments.first { it.map == m }; f.writeBytes(Http.bytes(e, m, seg.mapRange)) }
        }

        // 2) Akış dosyaları
        ctx.phase = "Birleştiriliyor"
        ctx.emit()
        e.status = com.movietrack.app.data.Status.MERGING
        ctx.saveNow()
        val streamFiles = plans.mapIndexed { si, p -> concat(si, p, skipAds) }

        // 3) Çıktı
        val base = meta["base"]?.takeIf { it.isNotBlank() } ?: e.fileName.substringBeforeLast('.')
        val folder = meta["folder"] ?: Category.VIDEO
        val extras = ArrayList<String>()
        var merged = false
        if (meta["mode"] != "separate") {
            val name = "$base.mp4"
            val out = OutputStore.create(app, name, "video/mp4", folder)
            try {
                val audioTracks = out.openRw(app).use { pfd ->
                    Muxer.mux(plans.indices.map { si ->
                        val p = plans[si]
                        // Ayrı ses izi seçildiyse ana akıştaki gömülü sesi yalnızca dil bilgisi yoksa al
                        Muxer.Input(streamFiles[si].path, takeVideo = p.role == "main", takeAudio = p.role == "audio" || plans.none { it.role == "audio" } || meta["muxedAudio"] == "1", lang = p.lang)
                    }, pfd.fileDescriptor)
                }
                out.publish(app)
                e.outputUri = out.encode()
                e.fileName = name
                merged = true
                if (audioTracks == 0) e.error = "Uyarı: dosyada ses izi yok. Kaynak yalnızca görüntü içeriyor olabilir; sayfadaki ana listeyi (çoklu kalite) seçip tekrar deneyin."
            } catch (t: Throwable) {
                out.delete(app)
                e.error = "Uyarı: MP4'e birleştirilemedi (${t.message}); dosyalar ayrı kaydedildi."
            }
        }
        if (!merged) {
            for ((si, p) in plans.withIndex()) {
                val f = streamFiles[si]
                val ext = if (p.fmp4) (if (p.role == "audio") "m4a" else "mp4") else "ts"
                val name = if (p.role == "audio") "$base.aud-${Lang.normalize(p.lang).ifEmpty { "und" }}.$ext" else "$base.$ext"
                val out = OutputStore.create(app, name, if (ext == "ts") "video/mp2t" else if (ext == "m4a") "audio/mp4" else "video/mp4", folder)
                copyTo(f, out)
                out.publish(app)
                if (p.role == "main") { e.outputUri = out.encode(); e.fileName = name } else extras += "${out.encode()}|${Lang.audLabel(p.lang)}"
            }
        }

        // 4) Altyazılar (SRT, oynatıcılar dosya adından otomatik tanır)
        for (line in (meta["subs"] ?: "").lines().filter { it.isNotBlank() }) {
            val (url, lang) = line.split('|').let { it[0] to it.getOrElse(1) { "" } }
            try {
                val srt = fetchSubtitle(url)
                if (srt.isBlank()) continue
                val name = "$base.${Lang.normalize(lang).ifEmpty { "und" }}.srt"
                val out = OutputStore.create(app, name, "application/x-subrip", folder)
                out.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { it.write(srt.toByteArray()) } }
                out.publish(app)
                extras += "${out.encode()}|${Lang.subLabel(lang)}"
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
            }
        }
        e.extraOutputs = extras.joinToString("\n")
        e.totalBytes = e.downloadedBytes
        dir.deleteRecursively()
    }

    private fun segFile(si: Int, i: Int) = File(dir, "${si}_$i.seg")
    private fun initFile(si: Int, url: String) = File(dir, "${si}_init_${url.hashCode().toUInt()}.seg")

    private suspend fun planHls(): List<Plan> {
        val plans = ArrayList<Plan>()
        // Tarayıcının yakaladığı liste varsa sunucuya yeniden sorma (bazı sunucular tek seferlik bağlantı verir)
        suspend fun fetch(u: String) = meta["inline:$u"] ?: Http.text(e, u)
        suspend fun load(url: String): Hls.Media {
            var u = url
            var text = fetch(u)
            if (Hls.isMaster(text)) {
                u = Hls.parseMaster(text, u).variants.firstOrNull()?.url ?: error("Varyant bulunamadı")
                text = fetch(u)
            }
            val m = Hls.parseMedia(text, u)
            if (m.drm) error("Bu yayın DRM ile korunuyor; indirilemez.")
            return m
        }
        val main = load(meta["variant"] ?: e.url)
        if (main.live) error("Canlı yayınlar bu sürümde desteklenmiyor.")
        plans += Plan("main", meta["muxedLang"] ?: "", "", main.segments, main.segments.firstOrNull()?.map != null)
        for (line in (meta["audios"] ?: "").lines().filter { it.isNotBlank() }) {
            val p = line.split('|')
            val m = load(p[0])
            plans += Plan("audio", p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, m.segments, m.segments.firstOrNull()?.map != null)
        }
        return plans
    }

    private suspend fun planDash(): List<Plan> {
        val mpd = Dash.parse(Http.text(e, e.url), e.url)
        if (mpd.drm) error("Bu DASH yayını DRM ile korunuyor; indirilemez.")
        val video = mpd.reps.firstOrNull { it.id == meta["variant"] } ?: mpd.reps.firstOrNull { it.kind == "video" }
        val plans = ArrayList<Plan>()
        video?.let { plans += Plan("main", "", "", it.segments, true) }
        for (line in (meta["audios"] ?: "").lines().filter { it.isNotBlank() }) {
            val p = line.split('|')
            mpd.reps.firstOrNull { it.id == p[0] && it.kind == "audio" }?.let { plans += Plan(if (plans.isEmpty()) "main" else "audio", it.lang, it.label, it.segments, true) }
        }
        if (plans.isEmpty()) error("İndirilecek temsil bulunamadı")
        return plans
    }

    private suspend fun decrypt(seg: Hls.Segment, data: ByteArray): ByteArray {
        val k = seg.key ?: return data
        if (k.method != "AES-128") error("Desteklenmeyen şifreleme: ${k.method}")
        val key = keyCache[k.url] ?: Http.bytes(e, k.url).also { keyCache[k.url] = it }
        val iv = ByteArray(16)
        if (k.iv != null) {
            val hex = k.iv.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            for (i in 0 until 16) iv[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var n = seg.seq
            for (i in 15 downTo 8) { iv[i] = (n and 0xff).toByte(); n = n shr 8 }
        }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private suspend fun concat(si: Int, p: Plan, skipAds: Boolean): File {
        val out = File(dir, "stream_$si.${if (p.fmp4) "mp4" else "ts"}")
        if (out.exists()) out.delete()
        FileOutputStream(out).use { os ->
            var lastMap: String? = null
            for ((i, seg) in p.segments.withIndex()) {
                coroutineContext.ensureActive()
                if (skipAds && seg.isAd) continue
                if (seg.map != null && seg.map != lastMap) { initFile(si, seg.map).let { if (it.exists()) os.write(it.readBytes()) }; lastMap = seg.map }
                val f = segFile(si, i)
                if (f.exists()) FileInputStream(f).use { it.copyTo(os, 1 shl 20) }
            }
        }
        return out
    }

    private fun copyTo(f: File, out: OutputFile) {
        out.openRw(app).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { os -> FileInputStream(f).use { it.copyTo(os, 1 shl 20) } } }
    }

    private suspend fun fetchSubtitle(url: String): String {
        val text = Http.text(e, url)
        val cues = ArrayList<Subtitles.Cue>()
        if (text.trimStart().startsWith("#EXTM3U")) {
            val m = Hls.parseMedia(text, url)
            val parts = m.segments.filter { !(meta["skipAds"] != "0" && it.isAd) }.map { Subtitles.parseVtt(Http.text(e, it.url)) }
            val base = parts.mapNotNull { it.second }.minOrNull()
            for ((c, ts) in parts) {
                val off = if (ts != null && base != null) (ts - base) / 90000.0 else 0.0
                c.forEach { cues += it.copy(start = it.start + off, end = it.end + off) }
            }
        } else if (text.trimStart().startsWith("WEBVTT")) {
            cues += Subtitles.parseVtt(text).first
        } else return text // SRT vb. olduğu gibi
        return Subtitles.toSrt(cues)
    }
}
