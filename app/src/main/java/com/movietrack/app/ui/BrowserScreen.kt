package com.movietrack.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movietrack.app.App
import com.movietrack.app.browser.BrowserEvent
import com.movietrack.app.browser.DetectedMedia
import com.movietrack.app.browser.Tab
import com.movietrack.app.browser.TabManager
import com.movietrack.app.data.BookmarkEntity
import com.movietrack.app.util.Format
import kotlinx.coroutines.launch

private data class DialogReq(val url: String, val kind: String?, val referer: String?, val title: String?, val manifest: String? = null, val ua: String? = null, val name: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onAddUrl: (String) -> Unit, snack: SnackbarHostState) {
    val tabs by TabManager.tabs.collectAsState()
    val current by TabManager.current.collectAsState()
    val tab = current ?: return
    val url by tab.url.collectAsState()
    val title by tab.title.collectAsState()
    val progress by tab.progress.collectAsState()
    val media by tab.media.collectAsState()
    val isHome by tab.isHome.collectAsState()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var address by remember(tab.id, url) { mutableStateOf(if (isHome) "" else url) }
    var menu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogReq?>(null) }
    var longPress by remember { mutableStateOf<BrowserEvent.LongPress?>(null) }
    var fullscreen by remember { mutableStateOf<BrowserEvent.Fullscreen?>(null) }
    var panel by remember { mutableStateOf<String?>(null) } // bookmarks | history

    LaunchedEffect(Unit) {
        TabManager.events.collect { ev ->
            when (ev) {
                is BrowserEvent.FileDownload -> dialog = DialogReq(ev.url, null, ev.referer, null, ua = ev.userAgent, name = URLUtil.guessFileName(ev.url, ev.contentDisposition, ev.mime))
                is BrowserEvent.LongPress -> longPress = ev
                is BrowserEvent.Fullscreen -> fullscreen = if (ev.view != null) ev else null
            }
        }
    }
    BackHandler(enabled = !isHome || fullscreen != null) {
        when {
            fullscreen != null -> fullscreen?.callback?.onCustomViewHidden()
            tab.webView.canGoBack() -> tab.webView.goBack()
            else -> tab.isHome.value = true
        }
    }

    val longestMedia = media.maxOfOrNull { it.duration } ?: 0.0
    val visibleMedia = media.filter { !it.isAd && !(it.duration in 0.1..59.0 && longestMedia > 300) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Adres çubuğu
            Row(Modifier.fillMaxWidth().background(MT.Bg).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tab.incognito) Icon(Icons.Outlined.Lock, "Gizli", tint = MT.Aud, modifier = Modifier.padding(4.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Ara veya adres yaz", color = MT.Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = MT.Muted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) { tab.load(address); focus.clearFocus() } }),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MT.Surface2, focusedBorderColor = MT.Accent, unfocusedContainerColor = MT.Surface, focusedContainerColor = MT.Surface),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MT.Text),
                )
                Box(Modifier.padding(start = 6.dp).size(30.dp).background(MT.Surface2, RoundedCornerShape(6.dp)).clickable { showTabs = true }, contentAlignment = Alignment.Center) {
                    Text("${tabs.size}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Yeni sekme") }, { menu = false; TabManager.newTab(null) })
                        DropdownMenuItem({ Text("Yeni gizli sekme") }, { menu = false; TabManager.newTab(null, incognito = true) })
                        if (!isHome) DropdownMenuItem({ Text("Yer imlerine ekle") }, { menu = false; scope.launch { App.instance.db.browser().addBookmark(BookmarkEntity(title = title, url = url)); snack.showSnackbar("Yer imi eklendi") } })
                        DropdownMenuItem({ Text("Yer imleri") }, { menu = false; panel = "bookmarks" })
                        DropdownMenuItem({ Text("Geçmiş") }, { menu = false; panel = "history" })
                        DropdownMenuItem({ Text(if (App.instance.settings.desktopMode) "Mobil site" else "Masaüstü sitesi") }, {
                            menu = false; App.instance.settings.desktopMode = !App.instance.settings.desktopMode; TabManager.applySettings(); tab.webView.reload()
                        })
                        DropdownMenuItem({ Text("Bağlantı ekle (URL ile indir)") }, { menu = false; onAddUrl("") })
                        if (!isHome) DropdownMenuItem({ Text("Bağlantıyı kopyala") }, { menu = false; copy(ctx, url) })
                    }
                }
            }
            if (progress in 1..99 && !isHome) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth(), color = MT.Accent, trackColor = MT.Bg)
            Box(Modifier.weight(1f)) {
                if (isHome) HomePage(onOpen = { tab.load(it) })
                else AndroidView(
                    factory = { _ -> FrameLayout(ctx) },
                    update = { holder -> attachWebView(holder, tab.webView) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Gezinme
            Row(Modifier.fillMaxWidth().background(MT.Bg), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { if (tab.webView.canGoBack()) tab.webView.goBack() else tab.isHome.value = true }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                IconButton(onClick = { if (isHome && url.isNotEmpty()) tab.isHome.value = false else tab.webView.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "İleri") }
                IconButton(onClick = { tab.isHome.value = true }) { Icon(Icons.Default.Home, "Ana sayfa") }
                IconButton(onClick = { tab.webView.reload() }) { Icon(Icons.Default.Refresh, "Yenile") }
            }
        }
        // 1DM'deki gibi yüzen indirme düğmesi: sayfada medya bulunursa sayı gösterir
        if (!isHome) {
            FloatingActionButton(onClick = { showMedia = true }, containerColor = if (visibleMedia.isNotEmpty()) MT.Accent else MT.Surface2,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 64.dp)) {
                BadgedBox(badge = { if (visibleMedia.isNotEmpty()) Badge { Text("${visibleMedia.size}") } }) { Icon(Icons.Default.Download, "Medya") }
            }
        }
    }

    fullscreen?.view?.let { v ->
        Dialog(onDismissRequest = { fullscreen?.callback?.onCustomViewHidden() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AndroidView({ FrameLayout(it).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v) } }, Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        }
    }

    if (showMedia) {
        ModalBottomSheet(onDismissRequest = { showMedia = false }, containerColor = MT.Surface) {
            MediaList(media, title, url) { m -> showMedia = false; dialog = DialogReq(m.url, m.kind, m.referer, title, m.manifestText) }
        }
    }

    if (showTabs) {
        ModalBottomSheet(onDismissRequest = { showTabs = false }, containerColor = MT.Surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sekmeler (${tabs.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { TabManager.newTab(null, true); showTabs = false }) { Text("+ Gizli") }
                IconButton(onClick = { TabManager.newTab(null); showTabs = false }) { Icon(Icons.Default.Add, "Yeni sekme") }
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(tabs, key = { it.id }) { t -> TabRow(t, t == tab, onSelect = { TabManager.select(t); showTabs = false }, onClose = { TabManager.close(t) }) }
            }
        }
    }

    panel?.let { p -> HistoryPanel(p, onOpen = { panel = null; tab.load(it) }, onDismiss = { panel = null }) }

    longPress?.let { lp ->
        val isImage = lp.type != WebView.HitTestResult.SRC_ANCHOR_TYPE
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { longPress = null }, containerColor = MT.Surface,
            title = { Text(lp.extra, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
            text = {
                Column {
                    TextButton(onClick = { longPress = null; TabManager.newTab(lp.extra) }) { Text("Yeni sekmede aç") }
                    TextButton(onClick = { longPress = null; TabManager.newTab(lp.extra, true) }) { Text("Gizli sekmede aç") }
                    TextButton(onClick = { longPress = null; dialog = DialogReq(lp.extra, null, lp.tab.currentUrl, null) }) { Text(if (isImage) "Resmi indir" else "Bağlantıyı indir") }
                    TextButton(onClick = { longPress = null; copy(ctx, lp.extra) }) { Text("Bağlantıyı kopyala") }
                }
            },
            confirmButton = {},
        )
    }

    dialog?.let { d ->
        AddDownloadDialog(initialUrl = d.url, referer = d.referer, pageTitle = d.title, kind = d.kind, manifestText = d.manifest, userAgent = d.ua ?: tab.webView.settings.userAgentString,
            presetName = d.name, inline = media.mapNotNull { m -> m.manifestText?.takeIf { it.isNotBlank() }?.let { m.url to it } }.toMap(),
            onDismiss = { dialog = null }, onQueued = { dialog = null; scope.launch { snack.showSnackbar("İndirme kuyruğa eklendi") } })
    }
}

private fun attachWebView(holder: FrameLayout, w: WebView) {
    if (w.parent === holder) return
    (w.parent as? ViewGroup)?.removeView(w)
    holder.removeAllViews()
    holder.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", text))
    App.instance.settings.lastClipboard = text
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaList(media: List<DetectedMedia>, pageTitle: String, pageUrl: String, onPick: (DetectedMedia) -> Unit) {
    val name = com.movietrack.app.download.Analyzer.displayName(pageTitle, pageUrl)
    var showAds by remember { mutableStateOf(false) }
    val longest = media.maxOfOrNull { it.duration } ?: 0.0
    // Kısa süreli yan videolar da (uzun bir video varken) muhtemel reklam
    val isAd = { m: DetectedMedia -> m.isAd || (m.duration in 0.1..59.0 && longest > 300) }
    val main = media.filter { !isAd(it) }.sortedWith(compareByDescending<DetectedMedia> { it.kind == "hls" || it.kind == "dash" }.thenByDescending { it.duration })
    val ads = media.filter(isAd)
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(name.ifBlank { "Bu sayfada bulunan medya" }, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(pageTitle, color = MT.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (main.isEmpty()) Text("Henüz video bulunamadı. Videoyu oynatmayı deneyin; akışlar (M3U8/MPD) ve dosyalar burada listelenir.", color = MT.Muted, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn {
            items(main.size, key = { main[it].url }) { i ->
                val m = main[i]
                val kindLabel = when (m.kind) { "hls" -> "M3U8"; "dash" -> "MPD"; "subtitle" -> "ALTYAZI"; else -> Format.extOf(m.url).uppercase().ifEmpty { m.kind.uppercase() } }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MT.Surface2, RoundedCornerShape(10.dp)).clickable { onPick(m) }.padding(12.dp)) {
                    Text(if (name.isNotBlank()) "$name${if (main.size > 1) " · Kaynak ${i + 1}" else ""}" else Format.fileNameFromUrl(m.url),
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${Format.host(m.url)} · ${Format.fileNameFromUrl(m.url)}", color = MT.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(kindLabel, MT.Accent)
                        if (m.duration > 0) Tag("⏱ ${Format.duration(m.duration.toLong())}")
                        if (m.lang.isNotEmpty()) LangTag("subtitle:${m.lang}")
                    }
                }
            }
            if (ads.isNotEmpty()) item(key = "ads") {
                Text(if (showAds) "Reklamları gizle (${ads.size})" else "Reklamlar gizlendi (${ads.size}) · göster", color = MT.Ad, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { showAds = !showAds }.padding(vertical = 12.dp))
            }
            if (showAds) items(ads.size, key = { "ad-" + ads[it].url }) { i ->
                val m = ads[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MT.Surface, RoundedCornerShape(10.dp)).clickable { onPick(m) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag("AD", MT.Ad)
                    Text("  ${Format.fileNameFromUrl(m.url)} · ${Format.host(m.url)}", color = MT.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TabRow(t: Tab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val title by t.title.collectAsState()
    val url by t.url.collectAsState()
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).background(if (active) MT.Surface2 else MT.Surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.incognito) Icon(Icons.Outlined.Lock, null, tint = MT.Aud, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MT.Muted, fontSize = 12.sp)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Kapat") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(onOpen: (String) -> Unit) {
    val bookmarks by App.instance.db.browser().bookmarks().collectAsState(initial = emptyList())
    val quick = listOf("Google" to "https://www.google.com", "DuckDuckGo" to "https://duckduckgo.com", "Vikipedi" to "https://tr.wikipedia.org", "Internet Archive" to "https://archive.org")
    Column(Modifier.fillMaxSize().background(MT.Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Movie Track", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Sitede videoyu açın; sağ alttaki indirme düğmesi bulunan medyayı gösterir.", color = MT.Muted)
        Text("Hızlı erişim", color = MT.Muted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (quick + bookmarks.map { it.title to it.url }).forEach { (n, u) ->
                Text(n, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.background(MT.Surface, RoundedCornerShape(10.dp)).clickable { onOpen(u) }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun HistoryPanel(kind: String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val dao = App.instance.db.browser()
    val scope = rememberCoroutineScope()
    val bookmarks by dao.bookmarks().collectAsState(initial = emptyList())
    val history by dao.history().collectAsState(initial = emptyList())
    val list: List<Pair<String, String>> = if (kind == "bookmarks") bookmarks.map { it.title to it.url } else history.map { it.title to it.url }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = MT.Surface,
        title = { Text(if (kind == "bookmarks") "Yer imleri" else "Geçmiş") },
        text = {
            LazyColumn {
                if (list.isEmpty()) item { Text("Boş", color = MT.Muted) }
                items(list) { (t, u) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(u) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MT.Muted, fontSize = 12.sp)
                        }
                        if (kind == "bookmarks") IconButton(onClick = { scope.launch { dao.removeBookmark(u) } }) { Icon(Icons.Default.Close, "Sil") }
                    }
                }
            }
        },
        confirmButton = {
            if (kind == "history") TextButton(onClick = { scope.launch { dao.clearHistory() } }) { Text("Geçmişi temizle") }
            TextButton(onClick = onDismiss) { Text("Kapat") }
        },
    )
}
