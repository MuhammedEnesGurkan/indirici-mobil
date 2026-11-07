package com.indirici.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.NewDownload
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import kotlinx.coroutines.launch

private val CATS = listOf(Category.VIDEO, Category.MUSIC, Category.DOC, Category.APP, Category.ARCHIVE, Category.IMAGE, Category.OTHER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(snack: SnackbarHostState, onNew: (DownloadRequest) -> Unit, onOpenDetail: (Long) -> Unit, onOpenBrowser: () -> Unit, onOpenQueue: () -> Unit) {
    val m = App.instance.downloads
    val all by m.all.collectAsState(initial = null)
    val live by m.live.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }
    val hidden = remember { mutableStateListOf<Long>() } // geri alınabilir silme bekleyenler
    var confirmDelete by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf<DownloadEntity?>(null) }
    val listState = rememberLazyListState()
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val selecting = selected.isNotEmpty()

    val importTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@launch
            val urls = Regex("https?://\\S+").findAll(text).map { it.value }.distinct().toList()
            urls.forEach { u -> m.add(NewDownload(url = u, fileName = Format.fileNameFromUrl(u))) }
            snack.showSnackbar("${urls.size} bağlantı sıraya eklendi")
        }
    }

    val items = (all ?: emptyList()).filter { it.id !in hidden }
    val activeCount = items.count { it.status in Status.ACTIVE }
    val errorCount = items.count { it.status == Status.ERROR }
    val list = items.filter {
        when (tab) {
            1 -> it.status in Status.ACTIVE || it.status == Status.PAUSED
            2 -> it.status == Status.DONE
            3 -> it.status == Status.ERROR
            else -> true
        }
    }.filter { cat == null || categoryOf(it) == cat }
        .filter { query.isBlank() || it.fileName.contains(query.trim(), ignoreCase = true) }

    BackHandler(selecting || searching) { if (selecting) selected.clear() else { searching = false; query = "" } }

    fun deleteWithUndo(e: DownloadEntity) {
        hidden += e.id
        val withFile = e.status != Status.DONE
        scope.launch {
            val r = snack.showSnackbar(if (withFile) "${middleEllipsis(e.fileName, 30)} silindi" else "${middleEllipsis(e.fileName, 30)} listeden kaldırıldı",
                actionLabel = "Geri al", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) hidden -= e.id else m.remove(e.id, withFile)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(bar.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            when {
                selecting -> TopAppBar(
                    title = { Text("${selected.size} seçildi") },
                    navigationIcon = { IconButton(onClick = { selected.clear() }) { Icon(Icons.Rounded.Close, "Seçimi kapat") } },
                    actions = {
                        IconButton(onClick = { selected.toList().forEach { m.pause(it) }; selected.clear() }) { Icon(Icons.Rounded.Pause, "Seçilenleri duraklat") }
                        IconButton(onClick = { selected.toList().forEach { m.resume(it) }; selected.clear() }) { Icon(Icons.Rounded.PlayArrow, "Seçilenleri sürdür") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Seçilenleri sil") }
                        IconButton(onClick = { selected.clear(); selected += list.map { it.id } }) { Icon(Icons.Rounded.SelectAll, "Tümünü seç") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                searching -> TopAppBar(
                    title = {
                        TextField(query, { query = it }, placeholder = { Text("İndirmelerde ara") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.surface, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface))
                    },
                    navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Rounded.ArrowBack, "Aramayı kapat") } },
                    actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Aramayı temizle") } },
                )
                else -> LargeTopAppBar(
                    title = { Text("İndirmeler") },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "İndirmelerde ara") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Menü") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Sıra ve zamanlayıcı") }, { menu = false; onOpenQueue() })
                                DropdownMenuItem({ Text("Tümünü duraklat") }, { menu = false; m.pauseAll() })
                                DropdownMenuItem({ Text("Tümünü sürdür") }, { menu = false; m.resumeAll() })
                                DropdownMenuItem({ Text("Metin dosyasından bağlantı ekle") }, { menu = false; importTxt.launch(arrayOf("text/*")) })
                                DropdownMenuItem({ Text("Tamamlananları listeden kaldır") }, { menu = false; m.clearFinished() })
                            }
                        }
                    },
                    scrollBehavior = bar,
                )
            }
        },
        floatingActionButton = {
            // Liste boşken boş durumun kendi düğmeleri var; FAB içeriğin üstüne binmesin
            if (!selecting && items.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { onNew(DownloadRequest()) },
                expanded = remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }.value,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Yeni indirme") },
                shape = RoundedCornerShape(20.dp),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), state = listState, contentPadding = PaddingValues(bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (activeCount > 0 && !selecting) item(key = "summary") { Summary(items, live) }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Tümü", if (activeCount > 0) "Aktif · $activeCount" else "Aktif", "Bitti", if (errorCount > 0) "Hatalı · $errorCount" else "Hatalı")
                        .forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) }
                }
            }
            item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATS) { c ->
                        FilterChip(cat == c, { cat = if (cat == c) null else c }, label = { Text(c) },
                            leadingIcon = if (cat == c) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null)
                    }
                }
            }
            if (all != null && list.isEmpty()) item(key = "empty") {
                when {
                    query.isNotBlank() -> EmptyState(Icons.Rounded.SearchOff, Icons.Rounded.ManageSearch, "“$query” bulunamadı",
                        "Dosya adının bir kısmını ya da uzantıyı dene: pdf, mp4, zip.", primary = "Aramayı temizle" to { query = "" }, primaryIcon = Icons.Rounded.Backspace, tonal = true)
                    !online(ctx) && items.any { it.status in Status.ACTIVE } -> EmptyState(Icons.Rounded.WifiOff, Icons.Rounded.Schedule, "Bağlantı bekleniyor",
                        "İnternet geri gelince indirmeler kaldıkları yerden kendiliğinden devam eder.")
                    tab == 3 -> EmptyState(Icons.Rounded.TaskAlt, Icons.Rounded.ThumbUp, "Hatalı indirme yok",
                        "Bir indirme yarıda kalırsa burada görünür; tek dokunuşla yeniden denersin.", iconTint = MaterialTheme.colorScheme.tertiary)
                    items.isEmpty() -> EmptyState(Icons.Rounded.Download, Icons.Rounded.Link, "Henüz indirme yok",
                        "Kopyaladığın bir bağlantıyı yapıştır ya da tarayıcıda bir sayfa aç; sayfadaki indirilebilir her şeyi gösteririz.",
                        primary = "Bağlantı yapıştır" to { onNew(DownloadRequest(url = clipText(ctx) ?: "", fromClipboard = clipText(ctx) != null)) },
                        primaryIcon = Icons.Rounded.ContentPaste, secondary = "Tarayıcıyı aç" to onOpenBrowser)
                    else -> EmptyState(Icons.Rounded.SearchOff, null, "Bu filtrede indirme yok", "Başka bir sekme ya da tür seç.",
                        primary = "Filtreyi temizle" to { cat = null; tab = 0 }, tonal = true)
                }
            }
            if (!online(ctx) && list.isNotEmpty() && items.any { it.status in Status.ACTIVE }) item(key = "offline") {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text("İnternet bağlantısı yok", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            items(list, key = { it.id }) { e ->
                val p = live[e.id]
                val k = statusOf(e, p).kind
                val sel = e.id in selected
                val click = {
                    when {
                        selecting -> if (sel) selected.remove(e.id) else selected.add(e.id)
                        e.status == Status.DONE -> Open.open(ctx, e)
                        else -> onOpenDetail(e.id)
                    }
                    Unit
                }
                val card = @Composable {
                    DownloadCard(e, p, sel, onClick = click, onLongClick = { if (!sel) selected.add(e.id) },
                        onPause = { m.pause(e.id) }, onResume = { m.resume(e.id) }, onOpen = { Open.open(ctx, e) }, onRefreshLink = { refresh = e })
                }
                Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
                    if (selecting) card() else SwipeRow(
                        pauseLabel = when (k) { Kind.PAUSED, Kind.ERROR, Kind.EXPIRED -> "Devam"; Kind.DONE -> null; else -> "Duraklat" },
                        onStartToEnd = { if (k == Kind.PAUSED || k == Kind.ERROR || k == Kind.EXPIRED) m.resume(e.id) else m.pause(e.id) },
                        onEndToStart = { deleteWithUndo(e) },
                    ) { card() }
                }
            }
        }
    }

    if (confirmDelete) {
        var withFiles by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("${selected.size} indirme silinsin mi?") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFiles, { withFiles = it })
                    Text("İndirilen dosyaları da sil")
                }
            },
            confirmButton = {
                Button(onClick = { selected.toList().forEach { m.remove(it, withFiles) }; selected.clear(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } })
    }
    refresh?.let { e -> RefreshLinkDialog(e, onDismiss = { refresh = null }) }
}

@Composable
private fun Summary(items: List<DownloadEntity>, live: Map<Long, com.movietrack.app.download.LiveProgress>) {
    val m = App.instance.downloads
    val c = MaterialTheme.colorScheme
    val running = items.filter { it.status == Status.RUNNING }
    val total = running.sumOf { live[it.id]?.speed ?: 0L }
    val left = running.maxOfOrNull { live[it.id]?.etaSec ?: 0L } ?: 0L
    val n = items.count { it.status in Status.ACTIVE }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.primaryContainer).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Speed, null, tint = c.onPrimaryContainer, modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (total > 0) speed(total) else "Bağlanıyor…", style = MaterialTheme.typography.headlineSmall, color = c.onPrimaryContainer)
            Text("$n indirme sürüyor" + if (left > 0) " · tümü ~${eta(left).removeSuffix(" kaldı")}" else "", style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
        }
        Button(onClick = { m.pauseAll() }, colors = ButtonDefaults.buttonColors(c.onPrimaryContainer, c.primaryContainer), contentPadding = PaddingValues(horizontal = 16.dp)) {
            Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tümünü duraklat")
        }
    }
}

/** Sağa kaydır = duraklat/devam, sola kaydır = sil (geri al ile). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(pauseLabel: String?, onStartToEnd: () -> Unit, onEndToStart: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { onStartToEnd(); false }
            SwipeToDismissBoxValue.EndToStart -> { onEndToStart(); true }
            else -> false
        }
    }, positionalThreshold = { it * 0.35f })
    val c = MaterialTheme.colorScheme
    SwipeToDismissBox(state, enableDismissFromStartToEnd = pauseLabel != null, backgroundContent = {
        val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(if (toEnd) c.secondaryContainer else c.errorContainer).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
        ) {
            val col = if (toEnd) c.onSecondaryContainer else c.onErrorContainer
            Icon(if (toEnd) (if (pauseLabel == "Devam") Icons.Rounded.PlayArrow else Icons.Rounded.Pause) else Icons.Rounded.Delete, null, tint = col)
            Spacer(Modifier.width(8.dp))
            Text(if (toEnd) pauseLabel ?: "" else "Sil", style = MaterialTheme.typography.labelLarge, color = col)
        }
    }) { content() }
}

@Composable
fun RefreshLinkDialog(e: DownloadEntity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var u by remember { mutableStateOf(clipText(ctx)?.takeIf { it != e.url } ?: "") }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, null) },
        title = { Text("Linki yenile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sunucu eski bağlantıyı kabul etmiyorsa videonun sayfasını yeniden açıp yeni bağlantıyı kopyala. İnen kısım korunur.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("Yeni bağlantı") })
                e.pageUrl?.let { page ->
                    TextButton(onClick = { com.movietrack.app.browser.TabManager.newTab(page); onDismiss() }) {
                        Icon(Icons.Rounded.Public, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sayfayı tarayıcıda aç", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { Button(enabled = u.startsWith("http"), onClick = { App.instance.downloads.refreshUrl(e.id, u.trim()); onDismiss() }) { Text("Devam et") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } })
}

fun online(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

