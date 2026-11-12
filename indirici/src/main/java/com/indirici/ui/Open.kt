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
