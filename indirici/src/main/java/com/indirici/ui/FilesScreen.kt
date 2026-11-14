package com.indirici.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.LibraryScan
import com.movietrack.app.download.OutputStore
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(onNew: (DownloadRequest) -> Unit, onOpenDownloads: () -> Unit, onOpenDetail: (Long) -> Unit = {}) {
    val ui = App.instance.ui
    val v by ui.version.collectAsState()
    v.hashCode()
    val all by App.instance.downloads.all.collectAsState(initial = null)
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    var cat by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(0) } // 0 tarih, 1 ad, 2 boyut
    var actions by remember { mutableStateOf<DownloadEntity?>(null) }
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val done = (all ?: emptyList()).filter { it.status == Status.DONE }
    val shown = done.filter { cat == null || categoryOf(it) == cat }.let { l ->
        when (sort) { 1 -> l.sortedBy { it.fileName.lowercase() }; 2 -> l.sortedByDescending { it.totalBytes }; else -> l.sortedByDescending { it.finishedAt.takeIf { f -> f > 0 } ?: it.createdAt } }
    }
    val grid = ui.filesGrid
    var needsAccess by remember { mutableStateOf(LibraryScan.needsAccess(ctx)) }
    var hideAccess by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var finder by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(LibraryScan.scanFolders(ctx)) }
    val scanDone: (Int) -> Unit = { n ->
        scanning = false
        needsAccess = LibraryScan.needsAccess(ctx)
        folders = LibraryScan.scanFolders(ctx)
        android.widget.Toast.makeText(ctx, if (n > 0) "$n eski dosya listeye eklendi" else "Listede olmayan dosya bulunamadı", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun rescan() {
        if (scanning) return
        scanning = true
        LibraryScan.scanAsync(ctx, scanDone)
    }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) rescan()
        else android.widget.Toast.makeText(ctx, "İzin verilmedi. Yalnızca bir klasöre ya da seçtiğin dosyalara da izin verebilirsin.", android.widget.Toast.LENGTH_LONG).show()
    }
    // Yalnızca bu klasör: indirme klasörünü değiştirmez, yalnızca taramaya eklenir
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        LibraryScan.addScanFolder(ctx, uri)
        folders = LibraryScan.scanFolders(ctx)
        rescan()
    }
    // Yalnızca seçilen dosyalar
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty() || scanning) return@rememberLauncherForActivityResult
        scanning = true
        LibraryScan.importPickedAsync(ctx, uris, scanDone)
    }
    // Eski kurulumdan kalan dosyalar artık bu uygulamanın değil: Android 11+ silmek için sistemin onayını ister
    var pendingDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val askDelete = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val e = pendingDelete
        pendingDelete = null
        if (r.resultCode == android.app.Activity.RESULT_OK && e != null) App.instance.downloads.remove(e.id, false)
    }
    val deleteFile: (DownloadEntity) -> Unit = { e ->
        val uris = (listOf(e.outputUri) + e.extraOutputs.lines().map { it.substringBeforeLast('|') })
            .mapNotNull { com.movietrack.app.download.OutputFile.decode(it)?.uri }
            .filter { it.authority == android.provider.MediaStore.AUTHORITY }
        if (android.os.Build.VERSION.SDK_INT >= 30 && com.movietrack.app.util.KV.decode(e.meta)["imported"] == "1" && uris.isNotEmpty()) {
            pendingDelete = e
            val sender = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
            askDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        } else App.instance.downloads.remove(e.id, true)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        bottomBar = { MiniPlayerBar() },
        topBar = {
            LargeTopAppBar(title = { Text("Dosyalar") }, scrollBehavior = bar, actions = {
                IconButton(onClick = { finder = true }, enabled = !scanning) {
                    Icon(Icons.Rounded.Refresh, "Eski dosyaları bul")
                }
                IconButton(onClick = { ui.filesGrid = !grid }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "Liste görünümü" else "Izgara görünümü") }
            })
        },
    ) { pad ->
        LazyVerticalGrid(GridCells.Fixed(if (grid) 2 else 1), Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 0.dp)) {
            if (needsAccess && !hideAccess && all != null && done.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "access") {
                OldFilesCard(
                    modifier = Modifier.padding(horizontal = if (grid) 0.dp else 16.dp).padding(bottom = 12.dp),
                    onFind = { finder = true },
                    onHide = { hideAccess = true },
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "cats") {
                val cats = Category.ALL.mapNotNull { k -> done.filter { categoryOf(it) == k }.takeIf { it.isNotEmpty() }?.let { k to it } }
                LazyRow(contentPadding = PaddingValues(horizontal = if (grid) 0.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cats, key = { it.first }) { (k, l) ->
                        val (bg, fg) = categoryColors(k)
                        val on = cat == k
                        Column(Modifier.width(124.dp).height(104.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                            .then(if (on) Modifier.background(c.primary.copy(alpha = 0.12f)) else Modifier)
                            .clickable(onClickLabel = if (on) "Filtreyi kaldır" else "$k dosyalarını göster") { cat = if (on) null else k }.padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(categoryIcon(k), null, tint = fg)
                            Column {
                                Text(if (on) "$k ✓" else k, style = MaterialTheme.typography.titleSmall, color = fg)
                                Text("${l.size} · ${Format.bytes(l.sumOf { it.totalBytes.coerceAtLeast(it.downloadedBytes) })}", style = MaterialTheme.typography.bodySmall, color = fg)
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Row(Modifier.padding(start = if (grid) 0.dp else 16.dp, end = if (grid) 0.dp else 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cat != null) cat!! else "Son dosyalar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    FilterChip(false, { sort = (sort + 1) % 3 }, label = { Text(listOf("Tarih", "Ad", "Boyut")[sort]) },
                        leadingIcon = { Icon(Icons.Rounded.SwapVert, "Sıralama", Modifier.size(18.dp)) })
                }
            }
            if (all != null && shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyState(Icons.Rounded.FolderOpen, Icons.Rounded.Download, "Henüz dosya yok", "Tamamlanan indirmeler türlerine göre burada toplanır.",
                    primary = "İndirmelere git" to onOpenDownloads, tonal = true)
            }
            items(shown, key = { it.id }) { e ->
                val size = Format.bytes(e.totalBytes.coerceAtLeast(e.downloadedBytes))
                val meta = "$size · ${Dates.short(e.finishedAt.takeIf { it > 0 } ?: e.createdAt)}"
                if (grid) Column(Modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerLow).combinedClickable(onClick = { openFile(ctx, e) }, onLongClick = { actions = e })) {
                    val (bg, fg) = categoryColors(categoryOf(e))
                    Box(Modifier.fillMaxWidth().height(104.dp).background(bg), contentAlignment = Alignment.Center) { Icon(categoryIcon(categoryOf(e)), null, tint = fg, modifier = Modifier.size(40.dp)) }
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(e.fileName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(size, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        IconButton(onClick = { actions = e }) { Icon(Icons.Rounded.MoreVert, "Seçenekler: ${e.fileName}") }
                    }
                } else Row(Modifier.fillMaxWidth().combinedClickable(onClick = { openFile(ctx, e) }, onLongClick = { actions = e }).heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FileThumb(categoryOf(e), size = 44.dp, radius = 12.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(middleEllipsis(e.fileName, 40), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    IconButton(onClick = { actions = e }) { Icon(Icons.Rounded.MoreVert, "Seçenekler: ${e.fileName}") }
                }
            }
        }
    }
    if (finder) FindOldSheet(
        folders = folders,
        fullAccess = LibraryScan.hasFullMediaAccess(ctx),
        scanning = scanning,
        onDismiss = { finder = false },
        onAllowAll = { finder = false; if (LibraryScan.hasFullMediaAccess(ctx)) rescan() else askMedia.launch(LibraryScan.mediaPermissions()) },
        onAddFolder = { finder = false; pickFolder.launch(LibraryScan.defaultFolderHint(ctx)) },
        onRemoveFolder = { LibraryScan.removeScanFolder(ctx, it); folders = LibraryScan.scanFolders(ctx); needsAccess = LibraryScan.needsAccess(ctx) },
        onPickFiles = { finder = false; pickFiles.launch(arrayOf("video/*", "audio/*", "application/x-subrip", "text/*", "application/octet-stream")) },
        onScan = { finder = false; rescan() },
    )
    actions?.let { e -> FileActions(e, onDismiss = { actions = null }, onNew = onNew, onDetail = { actions = null; onOpenDetail(e.id) }, onDelete = deleteFile) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActions(e: DownloadEntity, onDismiss: () -> Unit, onNew: (DownloadRequest) -> Unit, onDetail: () -> Unit = {}, onDelete: (DownloadEntity) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumb(categoryOf(e))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(e.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${Format.bytes(e.totalBytes.coerceAtLeast(e.downloadedBytes))} · ${Dates.short(e.finishedAt.takeIf { it > 0 } ?: e.createdAt)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = c.outlineVariant)
            ActionRow(Icons.Rounded.PlayCircle, "Aç") { onDismiss(); openFile(ctx, e) }
            if (categoryOf(e) == Category.VIDEO) ActionRow(Icons.Rounded.PlayCircle,
                if (e.extraOutputs.contains("subtitle:")) "Başka oynatıcıda aç (altyazılı)" else "Başka oynatıcıda aç") { onDismiss(); Open.openExternal(ctx, e) }
            ActionRow(Icons.Rounded.Share, "Paylaş") { onDismiss(); Open.share(ctx, e) }
            ActionRow(Icons.Rounded.Info, "Ayrıntılar") { onDetail() }
            ActionRow(Icons.Rounded.Edit, "Yeniden adlandır") { rename = true }
            if (e.url.isNotBlank()) ActionRow(Icons.Rounded.ContentCopy, "Bağlantıyı kopyala") { onDismiss(); copyText(ctx, e.url) }
            ActionRow(Icons.Rounded.ContentCopy, "Tanı raporunu kopyala") { onDismiss(); copyText(ctx, diagnosticReport(e)) }
            if (e.url.isNotBlank()) ActionRow(Icons.Rounded.Replay, "Tekrar indir") { onDismiss(); onNew(DownloadRequest(e.url, referer = e.referer, pageTitle = e.title, userAgent = e.userAgent)) }
            ActionRow(Icons.Rounded.Delete, "Sil", tint = c.error) { confirm = true }
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(e.fileName) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Yeniden adlandır") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Dosya adı") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    rename = false
                    val safe = Format.safeName(name)
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        if (e.outputUri != null && OutputStore.rename(ctx, e.outputUri!!, safe)) { e.fileName = safe; App.instance.db.downloads().update(e) }
                    }
                    onDismiss()
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Vazgeç") } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Dosya silinsin mi?") }, text = { Text("${e.fileName} cihazından silinecek.") },
        confirmButton = { Button(onClick = { confirm = false; onDelete(e); onDismiss() }, colors = ButtonDefaults.buttonColors(c.error, c.onError)) { Text("Sil") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Vazgeç") } })
}

/** Yeniden kurulumdan sonra eski indirmeleri görmek için izin isteyen kart. */
@Composable
private fun OldFilesCard(modifier: Modifier, onFind: () -> Unit, onHide: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.secondaryContainer).padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = c.onSecondaryContainer)
            Text("Eski indirmelerin görünmüyor mu?", style = MaterialTheme.typography.titleSmall, color = c.onSecondaryContainer, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            "Uygulamayı silip yeniden kurduysan dosyalar İndirilenler/indirici içinde duruyor. Android onları göstermem için izin istiyor. " +
                "Tüm videolara, yalnızca bir klasöre ya da yalnızca seçtiğin dosyalara izin verebilirsin.",
            style = MaterialTheme.typography.bodyMedium, color = c.onSecondaryContainer, modifier = Modifier.padding(top = 8.dp, end = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onHide) { Text("Gizle") }
            Button(onClick = onFind) { Text("Eski dosyaları bul") }
        }
    }
}

/** Eski dosyaları bulmak için ne kadar izin verileceğini seçtiren sayfa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindOldSheet(
    folders: List<android.net.Uri>, fullAccess: Boolean, scanning: Boolean,
    onDismiss: () -> Unit, onAllowAll: () -> Unit, onAddFolder: () -> Unit, onRemoveFolder: (android.net.Uri) -> Unit,
    onPickFiles: () -> Unit, onScan: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Eski dosyaları bul", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text("Ne kadar izin vereceğini sen seç. indirici'nin indirdiği videolarda küçük bir işaret var; tanımak için ona bakarım.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp))
            FinderOption(Icons.Rounded.VideoLibrary, if (fullAccess) "Tüm videolar (izin verildi)" else "Tüm videolara izin ver",
                "En kolayı. İndirilenler/indirici'deki her şey gelir; başka klasöre taşıdığın indirici videoları da işaretinden tanınır. Android 14'te izin penceresinde yalnızca seçtiklerine de izin verebilirsin.",
                onAllowAll)
            FinderOption(Icons.Rounded.FolderOpen, "Yalnızca bir klasöre izin ver",
                "Sadece seçtiğin klasörü görürüm. indirici klasörünü seçersen içindeki her şey (altyazılar dahil) gelir; başka bir klasörde yalnızca indirici'nin indirdikleri alınır. Birden çok klasör ekleyebilirsin.",
                onAddFolder)
            folders.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LibraryScan.folderLabel(f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { onRemoveFolder(f) }) { Icon(Icons.Rounded.Close, "Klasörün iznini kaldır: ${LibraryScan.folderLabel(f)}") }
                }
            }
            FinderOption(Icons.Rounded.FileOpen, "Yalnızca seçtiğim dosyalar",
                "Dosyaları tek tek seçersin, yalnızca onlara erişirim. Videoyla birlikte altyazısını da seçersen eşleştiririm.",
                onPickFiles)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = onScan, enabled = !scanning) { Text(if (scanning) "Taranıyor…" else "Şimdi tara") }
            }
        }
    }
}

@Composable
private fun FinderOption(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Icon(icon, null, tint = c.primary, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}


/** Müzik uygulama içinde mini oynatıcıda, diğerleri oynatıcıda ya da ilgili uygulamada açılır. */
fun openFile(ctx: android.content.Context, e: DownloadEntity) {
    if (categoryOf(e) == Category.MUSIC) MiniPlayer.play(ctx, e) else Open.open(ctx, e)
}
