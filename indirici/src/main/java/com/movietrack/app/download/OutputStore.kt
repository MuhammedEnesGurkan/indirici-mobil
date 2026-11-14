package com.movietrack.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.movietrack.app.App
import com.movietrack.app.R
import java.io.File

/**
 * İndirilen dosyanın son konumu. Dosya baştan son yerinde açılır ve parçalar doğrudan
 * konumlarına yazılır (geçici kopya yok → iki kat disk alanı gerekmez).
 *  - Kullanıcı klasör seçtiyse (SAF) o klasör
 *  - Android 10+: İndirilenler/MovieTrack/<alt klasör> (MediaStore)
 *  - Android 8–9: aynı yol, doğrudan dosya
 */
class OutputFile(val uri: Uri, private val pending: Boolean) {
    fun openRw(ctx: Context): ParcelFileDescriptor =
        ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("Dosya açılamadı: $uri")

    fun publish(ctx: Context) {
        if (pending && Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, v, null, null)
        }
        if (uri.scheme == "file") MediaScannerConnection.scanFile(ctx, arrayOf(uri.path), null, null)
    }

    fun delete(ctx: Context) = OutputStore.delete(ctx, uri.toString())

    fun encode() = "${if (pending) 1 else 0}|$uri"

    companion object {
        fun decode(s: String?): OutputFile? {
            if (s.isNullOrEmpty()) return null
            val i = s.indexOf('|')
            return if (i < 0) OutputFile(Uri.parse(s), false) else OutputFile(Uri.parse(s.substring(i + 1)), s.substring(0, i) == "1")
        }
    }
}

object OutputStore {
    fun create(ctx: Context, name: String, mime: String, subFolder: String): OutputFile {
        val type = mime.ifEmpty { "application/octet-stream" }
        App.instance.settings.folderUri?.let { tree ->
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
            if (root != null && root.canWrite()) {
                var dir: DocumentFile = root
                for (seg in subFolder.split('/').filter { it.isNotBlank() }) {
                    dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: dir.createDirectory(seg) ?: dir
                }
                val f = dir.createFile(type, name) ?: userError("Seçilen klasöre dosya yazılamıyor. Ayarlar › İndirmeler'den klasörü yeniden seçin.")
                return OutputFile(f.uri, false)
            }
        }
        val rel = listOf(Environment.DIRECTORY_DOWNLOADS, ctx.getString(R.string.download_folder), subFolder).filter { it.isNotBlank() }.joinToString("/")
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: userError("İndirilenler klasörüne yazılamadı. Telefonda yer olduğundan emin olun.")
            return OutputFile(uri, true)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), listOf(ctx.getString(R.string.download_folder), subFolder).filter { it.isNotBlank() }.joinToString("/"))
        dir.mkdirs()
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            val dot = name.lastIndexOf('.')
            f = File(dir, if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
            n++
        }
        f.createNewFile()
        return OutputFile(Uri.fromFile(f), false)
    }

    fun delete(ctx: Context, uri: String?) {
        if (uri.isNullOrEmpty()) return
        val u = Uri.parse(uri.substringAfter('|'))
        try {
            when (u.scheme) {
                "file" -> File(u.path ?: return).delete()
                else -> {
                    if (DocumentFile.isDocumentUri(ctx, u)) DocumentFile.fromSingleUri(ctx, u)?.delete()
                    else ctx.contentResolver.delete(u, null, null)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun rename(ctx: Context, uri: String, newName: String): Boolean = try {
        val u = Uri.parse(uri.substringAfter('|'))
        when {
            u.scheme == "file" -> { val f = File(u.path!!); f.renameTo(File(f.parentFile, newName)) }
            DocumentFile.isDocumentUri(ctx, u) -> DocumentFile.fromSingleUri(ctx, u)?.renameTo(newName) == true
            else -> ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) > 0
        }
    } catch (e: Exception) { false }

    /** Kullanıcıya gösterilecek yol. */
    fun displayFolder(subFolder: String): String =
        if (App.instance.settings.folderUri != null) "Seçilen klasör/$subFolder" else "İndirilenler/${App.instance.getString(R.string.download_folder)}/$subFolder"
}
