package com.indirici.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.download.OutputFile
import com.movietrack.app.player.PlayerActivity
import com.movietrack.app.util.Category
import java.io.File

/** İndirilen dosyaları açma / paylaşma. */
object Open {
    fun contentUri(ctx: Context, encoded: String?): Uri? {
        val u = OutputFile.decode(encoded)?.uri ?: return null
        return if (u.scheme == "file") FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(u.path!!)) else u
    }

    fun open(ctx: Context, e: DownloadEntity) {
        val uri = contentUri(ctx, e.outputUri) ?: return
        if (e.category == Category.VIDEO || e.category == Category.MUSIC) {
            val subs = e.extraOutputs.lines().filter { it.contains("subtitle:") }.mapNotNull { line ->
                contentUri(ctx, line.substringBeforeLast('|'))?.toString()?.let { it to line.substringAfterLast('|').removePrefix("subtitle:") }
            }
            val i = Intent(ctx, PlayerActivity::class.java)
                .setData(uri)
                .putExtra("title", e.fileName)
                .putExtra("subs", ArrayList(subs.map { it.first }))
                .putExtra("subLangs", ArrayList(subs.map { it.second }))
            ctx.startActivity(i)
            return
        }
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Aç"))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Bu dosyayı açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Videoyu başka bir oynatıcıda altyazılarıyla açar. Android 11+ başka uygulamaların İndirilenler'deki
     * .srt dosyalarını görmesine izin vermediği için altyazılar okuma izniyle birlikte gönderilir
     * (MX Player: "subs"/"subs.enable", VLC: "subtitles_location").
     */
    fun openExternal(ctx: Context, e: DownloadEntity) {
        val uri = contentUri(ctx, e.outputUri) ?: return
        val subs = e.extraOutputs.lines().filter { it.contains("subtitle:") }.mapNotNull { line ->
            contentUri(ctx, line.substringBeforeLast('|'))?.let { it to line.substringAfterLast('|').removePrefix("subtitle:") }
        }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime.ifEmpty { "video/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (subs.isNotEmpty()) {
            val uris = subs.map { it.first }.toTypedArray()
            i.putExtra("subs", uris)
            i.putExtra("subs.enable", arrayOf(uris.first()))
            i.putExtra("subs.name", subs.map { com.movietrack.app.util.Lang.displayName(it.second) }.toTypedArray())
            i.putExtra("subs.filename", subs.map { e.fileName.substringBeforeLast('.') + "." + it.second + ".srt" }.toTypedArray())
            i.putExtra("subtitles_location", uris.first().toString())
            // Okuma izni yalnızca intent'teki adreslere verilir: altyazılar ClipData'ya eklenir
            val clip = android.content.ClipData.newRawUri("video", uri)
            uris.forEach { clip.addItem(android.content.ClipData.Item(it)) }
            i.clipData = clip
        }
        try {
            ctx.startActivity(Intent.createChooser(i, "Oynatıcı seç").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Bu videoyu açacak oynatıcı yok", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(ctx: Context, e: DownloadEntity) {
        val uri = contentUri(ctx, e.outputUri) ?: return
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Paylaş"))
    }

    fun shareLink(ctx: Context, url: String) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Bağlantıyı paylaş"))
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
    com.movietrack.app.App.instance.settings.lastClipboard = text
    Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
}

/**
 * "Ayrıntıyı kopyala" / "Tanı raporunu kopyala" için tam rapor: ekrandaki kısa mesajın yanında
 * sürüm ve cihaz, indirmenin durumu, adresler, ilerleme, parçalar ve motorun tanı kaydı (hata
 * zinciri ve yığın izi dahil). Çerezler gizli kalsın diye rapora girmez.
 */
fun diagnosticReport(e: DownloadEntity): String {
    val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    fun at(ms: Long) = if (ms > 0) time.format(java.util.Date(ms)) else "-"
    val meta = com.movietrack.app.util.KV.decode(e.meta)
    val parts = e.parts.split(';').filter { it.isNotBlank() }
    return buildString {
        append("indirici ${com.movietrack.app.BuildConfig.VERSION_NAME} (${com.movietrack.app.BuildConfig.VERSION_CODE})")
        append(" · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        append(" · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("rapor: ${at(System.currentTimeMillis())}\n\n")
        append("dosya: ${e.fileName}\n")
        append("durum: ${e.status} · tür: ${e.type}${if (e.mime.isNotBlank()) " · ${e.mime}" else ""}\n")
        append("mesaj: ${e.error ?: "-"}\n")
        append("ilerleme: ${e.downloadedBytes} / ${e.totalBytes} bayt\n")
        append("başladı: ${at(e.createdAt)} · bitti: ${at(e.finishedAt)}\n")
        append("bağlantı: ${e.url}\n")
        append("sayfa: ${e.pageUrl ?: "-"}\n")
        append("referer: ${e.referer ?: "-"}\n")
        append("user-agent: ${e.userAgent ?: "-"}\n")
        append("çerez: ${if (e.cookies.isNullOrBlank()) "yok" else "var (rapora eklenmedi)"}\n")
        append("seçimler: ${e.labels.ifBlank { "-" }}\n")
        if (parts.isNotEmpty()) append("parçalar (${parts.size}): ${e.parts}\n")
        if (e.extraOutputs.isNotBlank()) append("ek dosyalar: ${e.extraOutputs.lines().count { it.isNotBlank() }}\n")
        meta.filterKeys { it != "diag" && !it.startsWith("inline:") }.takeIf { it.isNotEmpty() }?.let { m ->
            append("ek bilgi: ${m.entries.joinToString(", ") { "${it.key}=${it.value.take(200)}" }}\n")
        }
        append("\n--- tanı kaydı ---\n")
        append(meta["diag"]?.takeIf { it.isNotBlank() } ?: "(kayıt yok)")
        append('\n')
    }
}
