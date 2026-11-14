package com.movietrack.app.download

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.movietrack.app.App
import com.movietrack.app.R
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import com.movietrack.app.util.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * İndirme klasöründeki (ve kullanıcının izin verdiği yerlerdeki) dosyaları listeye geri ekler.
 *
 * İndirme listesi uygulamanın veritabanında durur; uygulama silinince liste gider ama dosyalar
 * İndirilenler/<klasör> içinde kalır. Android 10+ yeniden kurulan uygulamaya eski kurulumun
 * dosyalarını izinsiz göstermez. Kullanıcı ne kadar izin vermek istediğini seçer:
 *  - Tüm videolar (medya izni): İndirilenler/indirici'deki her şey + başka yerlere taşınmış,
 *    indirici işareti ([FileTag]) taşıyan videolar/sesler. Android 14'te "yalnızca seçilenler" de olur.
 *  - Yalnızca belli klasörler (SAF): indirici klasörüyse içindeki her şey (altyazılar dahil),
 *    başka klasörse yalnızca işaretli dosyalar
 *  - Yalnızca seçilen dosyalar: kullanıcı ne seçtiyse
 *  - Android 8–9: klasör doğrudan dosya olarak
 * İşaretli dosyalarda kaynak bağlantı ve başlık da geri gelir. Video ile aynı adı taşıyan altyazı
 * (`Ad.tr.srt`) ve ses (`Ad.aud-en.m4a`) dosyaları videonun ek dosyası olarak bağlanır. Zaten
 * listede olanlar (aynı adres, aynı MediaStore kaydı ya da aynı ad+boyut) atlanır.
 */
object LibraryScan {
    private class Found(
        val uri: String, // OutputFile.encode biçimi
        val name: String,
        val size: Long,
        val mime: String,
        val folder: String, // indirme klasörüne göre alt klasör ("Video/Dizi")
        val modified: Long,
        val mediaId: Long?,
        /** Dosya indirici klasöründe ya da kullanıcı kendisi seçti: işaret aranmadan eklenir */
        val trusted: Boolean = true,
    ) {
        var tag: FileTag.Info? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private const val MARKER = "library_scanned"

    /** Android sürümüne göre eski dosyaları görmek için istenecek izinler. */
    fun mediaPermissions(): Array<String> = when {
        // Android 14+: "Yalnızca seçilenler" (sınırlı erişim) seçeneği de sunulur
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMediaAccess(ctx: Context): Boolean = mediaPermissions().any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Tüm videolar görünüyor mu (Android 14'te "yalnızca seçilenler" verildiyse hayır). */
    fun hasFullMediaAccess(ctx: Context): Boolean = mediaPermissions().filter { !it.endsWith("USER_SELECTED") }.any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun persisted(ctx: Context, uri: String?): Boolean =
        uri != null && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isReadPermission }

    /** İndirme klasörü ya da eklenen klasörlerden biri hâlâ okunabiliyor mu (yeniden kurulumda kalıcı izin silinir). */
    fun hasFolderAccess(ctx: Context): Boolean {
        val s = App.instance.settings
        return persisted(ctx, s.folderUri) || s.scanFolders.any { persisted(ctx, it) }
    }

    /** İzin verilen ek klasörler (kalıcı izni sürenler). */
    fun scanFolders(ctx: Context): List<Uri> = App.instance.settings.scanFolders.filter { persisted(ctx, it) }.map(Uri::parse)

    /** Kullanıcının seçtiği klasörü kalıcı izinle tarama listesine ekler. */
    fun addScanFolder(ctx: Context, tree: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (_: Exception) { try { ctx.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        App.instance.settings.scanFolders = App.instance.settings.scanFolders + tree.toString()
    }

    fun removeScanFolder(ctx: Context, tree: Uri) {
        try { ctx.contentResolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        App.instance.settings.scanFolders = App.instance.settings.scanFolders - tree.toString()
    }

    /** Klasörün kullanıcıya gösterilecek adı ("Download/indirici"). */
    fun folderLabel(tree: Uri): String = try {
        DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Dahili depolama" }
    } catch (_: Exception) { tree.toString() }

    /** Klasör indirici'nin indirme klasörü (ya da içinde) mi: oradaki her dosya bizimdir. */
    private fun isOurFolder(ctx: Context, tree: Uri): Boolean {
        val name = ctx.getString(R.string.download_folder)
        return folderLabel(tree).split('/').any { it.equals(name, ignoreCase = true) }
    }

    /** Kullanıcının tek tek seçtiği dosyaları kalıcı izinle listeye ekler. */
    fun importPickedAsync(ctx: Context, uris: List<Uri>, done: (Int) -> Unit = {}) {
        scope.launch {
            val n = try {
                val picked = uris.mapNotNull { u ->
                    try { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    catch (_: Exception) { try { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
                    val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
                    ctx.contentResolver.query(u, cols, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val name = c.getString(0) ?: return@use null
                        Found("0|$u", name, if (c.isNull(1)) 0 else c.getLong(1), ctx.contentResolver.getType(u) ?: mimeOf(name), "", System.currentTimeMillis(), null)
                    }
                }
                scan(ctx, picked)
            } catch (_: Throwable) { 0 }
            withContext(Dispatchers.Main) { done(n) }
        }
    }

    /** Eski dosyaları görmek için izin gerekiyor mu (Dosyalar ekranındaki uyarı için). */
    fun needsAccess(ctx: Context): Boolean = !hasMediaAccess(ctx) && !hasFolderAccess(ctx)

    /** Sistem klasör seçicisini İndirilenler/<klasör> üzerinde açmak için başlangıç adresi. */
    fun defaultFolderHint(ctx: Context): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${Environment.DIRECTORY_DOWNLOADS}/${ctx.getString(R.string.download_folder)}",
    )

    /**
     * Kurulumdan sonra bir kez kendiliğinden tarar. İşaret yedeklenmeyen klasörde durur:
     * Android yedeği geri yüklese de yeniden kurulumda tarama yine çalışır.
     */
    fun autoOnce(ctx: Context) {
        val marker = File(ctx.noBackupFilesDir, MARKER)
        if (marker.exists()) return
        scope.launch {
            try { scan(ctx); marker.createNewFile() } catch (_: Throwable) {}
        }
    }

    /** Taramayı arka planda başlatır; bitince eklenen dosya sayısıyla `done` çağrılır (ana iş parçacığında). */
    fun scanAsync(ctx: Context, done: (Int) -> Unit = {}) {
        scope.launch {
            val n = try { scan(ctx) } catch (_: Throwable) { 0 }
            withContext(Dispatchers.Main) { done(n) }
        }
    }

    /** Klasörü tarar ve listede olmayan dosyaları tamamlanmış indirme olarak ekler. Eklenen sayı döner. */
    private suspend fun scan(ctx: Context, picked: List<Found> = emptyList()): Int = lock.withLock {
        val found = LinkedHashMap<String, Found>() // ad|boyut → ilk bulunan (seçilen/SAF önce: tam erişim)
        fun add(list: List<Found>) = list.forEach { found.putIfAbsent("${it.name}|${it.size}", it) }
        add(picked)
        runCatching { add(fromTree(ctx, App.instance.settings.folderUri, true)) }
        for (t in scanFolders(ctx)) runCatching { add(fromTree(ctx, t.toString(), isOurFolder(ctx, t))) }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { add(fromMediaStore(ctx)) }
            // Başka klasörlere taşınmış indirici dosyaları: yalnızca işaretliler alınır
            if (hasMediaAccess(ctx)) runCatching { add(fromMediaStoreElsewhere(ctx)) }
        } else runCatching { add(fromFiles(ctx)) }
        if (found.isEmpty()) return 0

        // İşaretleri oku: kaynak bağlantı ve başlık geri gelir, işaretsiz "yabancı" dosyalar elenir
        var reads = 0
        for (f in found.values) {
            if (!FileTag.taggable(f.name) || reads++ >= MAX_TAG_READS) continue
            OutputFile.decode(f.uri)?.uri?.let { f.tag = FileTag.read(ctx, it) }
        }

        val dao = App.instance.db.downloads()
        val existing = dao.list()
        val knownUris = existing.flatMap { e -> listOfNotNull(e.outputUri) + e.extraOutputs.lines().map { it.substringBeforeLast('|') } }
            .mapNotNull { OutputFile.decode(it)?.uri?.toString() }.toHashSet()
        val byMediaId = existing.mapNotNull { e -> mediaIdOf(e.outputUri)?.let { it to e } }.toMap()
        val knownNames = existing.map { "${it.fileName}|${it.totalBytes}" }.toHashSet()
        // Süren indirmelerin dosyaları (SAF/eski Android'de baştan görünür) eklenmez
        val busyNames = existing.filter { it.status != Status.DONE }.map { it.fileName }.toHashSet()

        // Yan dosyalar: aynı klasörde "Ad.<dil>.srt" ve "Ad.aud-<dil>.m4a" videonun ek dosyasıdır
        val all = found.values.toList()
        val sidecarOf = HashMap<Found, Pair<Found, String>>() // yan dosya → (video, etiket)
        val videos = all.filter { Category.of(it.name, it.mime) == Category.VIDEO }
        for (v in videos) {
            val base = v.name.substringBeforeLast('.')
            for (f in all) {
                if (f === v || f.folder != v.folder || !f.name.startsWith("$base.")) continue
                val ext = Format.extOf(f.name)
                val mid = f.name.removePrefix("$base.").substringBeforeLast('.', "")
                val label = when {
                    ext in SUB_EXT -> Lang.subLabel(mid)
                    ext in AUDIO_EXT && mid.startsWith("aud-") -> Lang.audLabel(mid.removePrefix("aud-"))
                    ext in AUDIO_EXT && mid.length in 2..3 -> Lang.audLabel(mid)
                    else -> null
                } ?: continue
                sidecarOf.putIfAbsent(f, v to label)
            }
        }

        var added = 0
        for (f in all) {
            if (f in sidecarOf || f.size <= 0) continue
            if (!f.trusted && f.tag == null) continue
            val plain = OutputFile.decode(f.uri)?.uri?.toString()
            if ((plain != null && plain in knownUris) || f.name in busyNames || "${f.name}|${f.size}" in knownNames) continue
            // Yedekten dönen eski kayıt: dosya aynı, yalnızca okunabilir adresi güncellenir
            val restored = f.mediaId?.let { byMediaId[it] }
            if (restored != null) {
                if (restored.status == Status.DONE) { restored.outputUri = f.uri; dao.update(restored) }
                continue
            }
            val extras = sidecarOf.filterValues { it.first === f }.map { (s, p) -> "${s.uri}|${p.second}" }
            val t = f.tag
            val labels = (t?.labels?.split(' ').orEmpty() + extras.map { it.substringAfterLast('|') }).filter { it.isNotBlank() }.distinct()
            dao.insert(
                DownloadEntity(
                    url = t?.url ?: "", type = "direct", fileName = f.name, mime = f.mime,
                    category = Category.of(f.name, f.mime), status = Status.DONE,
                    totalBytes = f.size, downloadedBytes = f.size, outputUri = f.uri,
                    extraOutputs = extras.joinToString("\n"),
                    pageUrl = t?.pageUrl,
                    title = t?.title ?: f.name.substringBeforeLast('.'),
                    labels = labels.joinToString(" "),
                    meta = com.movietrack.app.util.KV.encode(mapOf("imported" to "1", "folder" to f.folder, "tagged" to if (t != null) "1" else "0")),
                    createdAt = t?.createdAt?.takeIf { it > 0 } ?: f.modified, finishedAt = f.modified,
                ),
            )
            added++
        }
        added
    }

    private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")
    private val AUDIO_EXT = setOf("m4a", "aac", "mp3", "opus", "ogg", "ac3", "eac3", "mka")

    private fun mediaIdOf(encoded: String?): Long? {
        val u = OutputFile.decode(encoded)?.uri ?: return null
        return if (u.authority == MediaStore.AUTHORITY) u.lastPathSegment?.toLongOrNull() else null
    }

    private fun rootRel(ctx: Context) = "${Environment.DIRECTORY_DOWNLOADS}/${ctx.getString(R.string.download_folder)}/"

    private fun fromMediaStore(ctx: Context): List<Found> =
        queryMediaStore(ctx, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${rootRel(ctx)}%"), trusted = true)

    /** İndirici klasörü dışındaki videolar/sesler: işaretli olan taşınmış indirici dosyası sayılır. */
    private fun fromMediaStoreElsewhere(ctx: Context): List<Found> =
        queryMediaStore(
            ctx,
            "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO})",
            arrayOf("${rootRel(ctx)}%"), trusted = false,
        ).filter { FileTag.taggable(it.name) }.take(1000)

    private fun queryMediaStore(ctx: Context, selection: String, args: Array<String>, trusted: Boolean): List<Found> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val root = rootRel(ctx)
        val out = ArrayList<Found>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, selection, args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val rel = c.getString(4) ?: ""
                val collection = when (c.getInt(6)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                out += Found(
                    uri = "0|${ContentUris.withAppendedId(collection, id)}", name = name, size = c.getLong(2),
                    mime = c.getString(3) ?: "", folder = if (trusted) rel.removePrefix(root).trim('/') else rel.trim('/'),
                    modified = c.getLong(5) * 1000, mediaId = id, trusted = trusted,
                )
            }
        }
        return out
    }

    private fun fromFiles(ctx: Context): List<Found> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ctx.getString(R.string.download_folder))
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(6).filter { it.isFile && !it.name.startsWith(".") }.take(MAX).map { f ->
            Found(
                uri = "0|${Uri.fromFile(f)}", name = f.name, size = f.length(), mime = mimeOf(f.name),
                folder = f.parentFile?.relativeTo(dir)?.path?.replace(File.separatorChar, '/') ?: "",
                modified = f.lastModified(), mediaId = null,
            )
        }.toList()
    }

    private fun fromTree(ctx: Context, treeUri: String?, trusted: Boolean): List<Found> {
        if (!persisted(ctx, treeUri)) return emptyList()
        val tree = Uri.parse(treeUri)
        val out = ArrayList<Found>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        fun walk(docId: String, folder: String, depth: Int) {
            if (depth > 6 || out.size >= MAX) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, if (folder.isEmpty()) name else "$folder/$name", depth + 1)
                    } else if (!name.startsWith(".")) {
                        out += Found(
                            uri = "0|${DocumentsContract.buildDocumentUriUsingTree(tree, id)}", name = name,
                            size = if (c.isNull(3)) 0 else c.getLong(3), mime = mime.ifEmpty { mimeOf(name) },
                            folder = folder, modified = if (c.isNull(4)) System.currentTimeMillis() else c.getLong(4), mediaId = null,
                            trusted = trusted,
                        )
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }

    private const val MAX = 5000
    private const val MAX_TAG_READS = 1500

    private fun mimeOf(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(Format.extOf(name)) ?: ""
}
