package com.indirici.ui

import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movietrack.app.App
import com.movietrack.app.browser.BrowserEvent
import com.movietrack.app.browser.DetectedMedia
import com.movietrack.app.browser.Detection
import com.movietrack.app.browser.Tab
import com.movietrack.app.browser.TabManager
import com.movietrack.app.data.BookmarkEntity
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.Hls
import com.movietrack.app.util.Format
import com.movietrack.app.util.Lang
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(snack: SnackbarHostState, onDownload: (DownloadRequest) -> Unit) {
    val current by TabManager.current.collectAsState()
    LaunchedEffect(current) { if (current == null) TabManager.newTab(null) }
    val tab = current ?: return
    // Gizli sekme temadan bağımsız kendi yüzeyini kullanır
    if (tab.incognito) MaterialTheme(colorScheme = IncognitoColors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(color = MaterialTheme.colorScheme.surface) { BrowserBody(tab, snack, onDownload) }
    } else BrowserBody(tab, snack, onDownload)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserBody(tab: Tab, snack: SnackbarHostState, onDownload: (DownloadRequest) -> Unit) {
    val tabs by TabManager.tabs.collectAsState()
    val url by tab.url.collectAsState()
    val title by tab.title.collectAsState()
    val progress by tab.progress.collectAsState()
    val media by tab.media.collectAsState()
    val isHome by tab.isHome.collectAsState()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    var address by remember(tab.id, url, isHome) { mutableStateOf(if (isHome) "" else url) }
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var selectAll by remember { mutableStateOf(false) }
    LaunchedEffect(selectAll) { if (selectAll) { kotlinx.coroutines.delay(400); selectAll = false } }
    var menu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var longPress by remember { mutableStateOf<BrowserEvent.LongPress?>(null) }
    var fullscreen by remember { mutableStateOf<BrowserEvent.Fullscreen?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }

    fun ua() = tab.webView.settings.userAgentString
    fun request(m: DetectedMedia, preselect: String? = null) = DownloadRequest(
        url = m.url, kind = m.kind, referer = m.referer, pageTitle = title, manifest = m.manifestText, userAgent = ua(), preselect = preselect,
        pageSubtitles = media.filter { it.kind == "subtitle" }.map { it.url to subLang(it) },
        inline = media.mapNotNull { x -> x.manifestText?.takeIf { it.isNotBlank() }?.let { x.url to it } }.toMap(),
    )

    LaunchedEffect(Unit) {
        TabManager.events.collect { ev ->
            when (ev) {
                is BrowserEvent.FileDownload -> onDownload(DownloadRequest(ev.url, null, ev.referer, null, userAgent = ev.userAgent, name = URLUtil.guessFileName(ev.url, ev.contentDisposition, ev.mime)))
                is BrowserEvent.LongPress -> longPress = ev
                is BrowserEvent.Fullscreen -> fullscreen = if (ev.view != null) ev else null
            }
        }
    }
    BackHandler(enabled = !isHome || fullscreen != null || editing) {
        when {
            fullscreen != null -> fullscreen?.callback?.onCustomViewHidden()
            editing -> { editing = false; focus.clearFocus() }
            tab.webView.canGoBack() -> tab.webView.goBack()
            else -> tab.isHome.value = true
        }
    }

    val longest = media.maxOfOrNull { it.duration } ?: 0.0
    val visible = media.filter { !it.isAd && !(it.duration in 0.1..59.0 && longest > 300) }
    val children = Detection.childToMaster(media)
    // YouTube videoları parça parça (uzantısız) aktığı için algılanmaz; video sayfasında doğrudan YouTube'a sorulur
    val ytPage = !isHome && com.movietrack.app.download.YouTube.id(url) != null
    val count = if (ytPage) 1 else visible.count { it.url !in children }

    // Adres çubuğu: varsayılan altta (tek elle erişim); Ayarlar › Tarayıcı'dan ya da menüden üste alınabilir
    val uiV by App.instance.ui.version.collectAsState()
    uiV.hashCode()
    val barTop = App.instance.ui.addressBarTop
    val addressBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().background(c.surfaceContainer).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerHighest).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when { tab.incognito -> Icons.Rounded.VisibilityOff; !isHome && url.startsWith("https") && !editing -> Icons.Rounded.Lock; else -> Icons.Rounded.Search }, null,
                    tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                // İlk dokunuş adresin tamamını seçer (yazınca ya da silince hepsi gider); ikinci dokunuş imleci koyar
                BasicTextField(
                    value = if (editing) field else TextFieldValue(if (isHome) "" else Format.host(url).removePrefix("www.") + url.substringAfter(Format.host(url), "").take(60)),
                    onValueChange = { v ->
                        if (editing) {
                            // Odaklayan dokunuş imleci de taşır; seçimi o tek seferlik koru
                            field = if (selectAll && v.text == field.text) v.copy(selection = TextRange(0, v.text.length)) else v
                            selectAll = false
                            address = field.text
                        }
                    },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface), cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) { tab.load(address); editing = false; focus.clearFocus() } }),
                    modifier = Modifier.weight(1f).onFocusChanged { f ->
                        if (f.isFocused && !editing) {
                            address = if (isHome) "" else url
                            field = TextFieldValue(address, TextRange(0, address.length))
                            selectAll = address.isNotEmpty()
                            editing = true
                        } else if (!f.isFocused) editing = false
                    },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if ((editing && field.text.isEmpty()) || (!editing && isHome)) Text(if (tab.incognito) "Gizli · ara ya da adres yaz" else "Ara ya da adres yaz", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                            inner()
                        }
                    },
                )
            }
            AnimatedVisibility(!isHome && count > 0, enter = scaleIn(), exit = scaleOut()) {
                MediaButton(count) {
                    if (ytPage) onDownload(DownloadRequest(url, "youtube", url, title.removeSuffix(" - YouTube"), userAgent = ua()))
                    else showMedia = true
                }
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { showTabs = true }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).border(2.dp, c.onSurfaceVariant, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Text("${tabs.size}", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Tarayıcı menüsü") }
                DropdownMenu(menu, { menu = false }) {
                    Row(Modifier.padding(horizontal = 4.dp)) {
                        IconButton(onClick = { menu = false; if (tab.webView.canGoBack()) tab.webView.goBack() else tab.isHome.value = true }) { Icon(Icons.Rounded.ArrowBack, "Geri") }
                        IconButton(onClick = { menu = false; if (isHome && url.isNotEmpty()) tab.isHome.value = false else tab.webView.goForward() }) { Icon(Icons.Rounded.ArrowForward, "İleri") }
                        IconButton(onClick = { menu = false; tab.webView.reload() }) { Icon(Icons.Rounded.Refresh, "Yenile") }
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Yeni sekme") }, { menu = false; TabManager.newTab(null) })
                    DropdownMenuItem({ Text("Yeni gizli sekme") }, { menu = false; TabManager.newTab(null, incognito = true) })
                    DropdownMenuItem({ Text("Açılış sayfası") }, { menu = false; tab.isHome.value = true })
                    DropdownMenuItem({ Text(if (barTop) "Adres çubuğunu alta al" else "Adres çubuğunu üste al") }, { menu = false; App.instance.ui.addressBarTop = !barTop })
                    if (!isHome) DropdownMenuItem({ Text("Yer imlerine ekle") }, { menu = false; scope.launch { App.instance.db.browser().addBookmark(BookmarkEntity(title = title, url = url)); snack.showSnackbar("Yer imi eklendi") } })
                    DropdownMenuItem({ Text("Yer imleri") }, { menu = false; panel = "bookmarks" })
                    DropdownMenuItem({ Text("Geçmiş") }, { menu = false; panel = "history" })
                    DropdownMenuItem({ Text(if (App.instance.settings.desktopMode) "Mobil siteyi iste" else "Masaüstü siteyi iste") }, {
                        menu = false; App.instance.settings.desktopMode = !App.instance.settings.desktopMode; TabManager.applySettings(); tab.webView.reload()
                    })
                    if (!isHome) DropdownMenuItem({ Text("Bağlantıyı kopyala") }, { menu = false; copyText(ctx, url) })
                    if (!isHome) DropdownMenuItem({ Text("Bu sayfayı indir") }, { menu = false; onDownload(DownloadRequest(url, null, url, title, userAgent = ua())) })
                }
            }
        }
    }
    // Konum değişince düzen baştan kurulur (yoksa WebView eski yerinde kalıyor, değişiklik sekme değişene dek görünmüyordu)
    androidx.compose.runtime.key(barTop) {
    Column(Modifier.fillMaxSize().background(c.surface)) {
        if (barTop) addressBar()
        Box(Modifier.weight(1f)) {
            if (isHome) StartPage(tab.incognito, onOpen = { tab.load(it) })
            else Column {
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().height(3.dp), color = c.primary, trackColor = c.surface, drawStopIndicator = {})
                AndroidView(factory = { FrameLayout(it) }, update = { attachWebView(it, tab.webView) }, modifier = Modifier.fillMaxSize())
            }
        }
        if (!barTop) addressBar()
    }
    }

    fullscreen?.view?.let { v ->
        Dialog(onDismissRequest = { fullscreen?.callback?.onCustomViewHidden() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AndroidView({ FrameLayout(it).apply { (v.parent as? ViewGroup)?.removeView(v); addView(v) } }, Modifier.fillMaxSize().background(Color.Black))
        }
    }

    if (showMedia) ModalBottomSheet(onDismissRequest = { showMedia = false }, containerColor = c.surfaceContainerLow) {
        MediaList(media, title, url) { picked ->
            showMedia = false
            val masterUrl = children[picked.url]
            val m = masterUrl?.let { u -> media.firstOrNull { it.url == u } } ?: picked
            onDownload(request(m, if (m !== picked) picked.url else null))
        }
    }

    if (showTabs) TabManagerScreen(tab, onClose = { showTabs = false })

    panel?.let { p -> HistoryPanel(p, onOpen = { panel = null; tab.load(it) }, onDismiss = { panel = null }) }

    longPress?.let { lp ->
        val isImage = lp.type != WebView.HitTestResult.SRC_ANCHOR_TYPE
        AlertDialog(
            onDismissRequest = { longPress = null },
            title = { Text(lp.extra, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
            text = {
                Column {
                    listOf(
                        (if (isImage) "Resmi indir" else "Bağlantıyı indir") to { onDownload(DownloadRequest(lp.extra, null, lp.tab.currentUrl, null, userAgent = ua())) },
                        "Yeni sekmede aç" to { TabManager.newTab(lp.extra); Unit },
                        "Gizli sekmede aç" to { TabManager.newTab(lp.extra, true); Unit },
                        "Bağlantıyı kopyala" to { copyText(ctx, lp.extra) },
                    ).forEach { (label, act) ->
                        TextButton(onClick = { longPress = null; act() }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

/** Sayfada medya bulununca beliren, sayaçlı indirme düğmesi. */
@Composable
private fun MediaButton(count: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp).scale(s).clip(RoundedCornerShape(16.dp)).background(c.primary).clickable(onClickLabel = "$count indirilebilir medya bulundu, listeyi aç", onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Download, "$count medya bulundu", tint = c.onPrimary)
        }
        Badge(Modifier.align(Alignment.TopEnd)) { Text("$count") }
    }
}

@Composable
private fun StartPage(incognito: Boolean, onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    if (incognito) {
        EmptyState(Icons.Rounded.VisibilityOff, null, "Gizli sekme",
            "Bu sekmeyi kapattığında geçmiş, çerezler ve form bilgileri silinir. İndirdiğin dosyalar cihazında kalır.",
            modifier = Modifier.padding(top = 80.dp), iconTint = c.onSurface)
        return
    }
    val dao = App.instance.db.browser()
    val bookmarks by dao.bookmarks().collectAsState(initial = emptyList())
    val history by dao.history().collectAsState(initial = emptyList())
    val quick = listOf("Vimeo" to "https://vimeo.com", "Archive" to "https://archive.org", "Wikipedia" to "https://tr.wikipedia.org", "Dailymotion" to "https://www.dailymotion.com",
        "Pexels" to "https://www.pexels.com/videos", "GitHub" to "https://github.com", "DuckDuckGo" to "https://duckduckgo.com")
    val shortcuts = (bookmarks.map { it.title to it.url } + quick).distinctBy { it.second }.take(8)
    val palette = listOf(c.primaryContainer to c.onPrimaryContainer, c.tertiaryContainer to c.onTertiaryContainer, c.secondaryContainer to c.onSecondaryContainer, c.surfaceContainerHighest to c.onSurface)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = c.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(8.dp))
            Text("indirici", style = MaterialTheme.typography.headlineMedium)
        }
        Text("Bir sayfa aç; video ya da dosya bulunduğunda adres çubuğunun yanında indirme düğmesi belirir.",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        // 4 sütunluk kısayol ızgarası
        shortcuts.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, (n, u) ->
                    val (bg, fg) = palette[(n.hashCode() and 0x7fffffff) % palette.size]
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onOpen(u) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(bg), contentAlignment = Alignment.Center) {
                            Text(n.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = fg, fontWeight = FontWeight.Bold)
                        }
                        Text(n, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i == row.lastIndex) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (history.isNotEmpty()) Column {
            Text("Son ziyaret edilenler", style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Column(Modifier.clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                history.distinctBy { it.url }.take(4).forEach { h ->
                    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow).clickable { onOpen(h.url) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = c.onSurfaceVariant)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.url, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaList(media: List<DetectedMedia>, pageTitle: String, pageUrl: String, onPick: (DetectedMedia) -> Unit) {
    val c = MaterialTheme.colorScheme
    val name = Analyzer.displayName(pageTitle, pageUrl)
    var showAds by remember { mutableStateOf(false) }
    val longest = media.maxOfOrNull { it.duration } ?: 0.0
    val isAd = { m: DetectedMedia -> m.isAd || (m.duration in 0.1..59.0 && longest > 300) }
    val children = Detection.childToMaster(media)
    val main = media.filter { !isAd(it) && it.url !in children }.sortedWith(compareByDescending<DetectedMedia> { it.kind == "hls" || it.kind == "dash" }.thenByDescending { it.duration })
    val ads = media.filter(isAd)
    val subsList = main.filter { it.kind == "subtitle" }
    val videos = main - subsList.toSet()
    Column(Modifier.padding(bottom = 24.dp)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
            Text(if (videos.isNotEmpty()) "${videos.size} medya bulundu" else "Henüz medya yok", style = MaterialTheme.typography.titleLarge)
            Text(listOf(name, Format.host(pageUrl)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (videos.isEmpty() && subsList.isEmpty()) Text("Videoyu oynatmayı dene; akışlar ve dosyalar oynatma başlayınca burada listelenir.",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(videos.size, key = { videos[it].url }) { i ->
                val m = videos[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).clickable { onPick(m) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(72.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Movie, null, tint = c.onPrimaryContainer)
                        if (m.duration > 0) Text(Format.duration(m.duration.toLong()), style = MaterialTheme.typography.labelSmall, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 3.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (name.isNotBlank()) (name + (if (videos.size > 1) " · Kaynak ${i + 1}" else "")) else Format.fileNameFromUrl(m.url),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sourceDesc(m) + if ((m.kind == "hls" || m.kind == "dash") && subsList.isNotEmpty()) " · +${subsList.size} altyazı" else "",
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.Download, null, tint = c.primary, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            if (subsList.isNotEmpty()) item(key = "subs-h") {
                Text("Altyazılar", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
            }
            items(subsList.size, key = { "sub-" + subsList[it].url }) { i ->
                val m = subsList[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceContainer).clickable { onPick(m) }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Subtitles, null, tint = c.onSurfaceVariant)
                    Text("${Lang.displayName(subLang(m))} · ${Format.extOf(m.url).uppercase()}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ads.isNotEmpty()) item(key = "ads") {
                TextButton(onClick = { showAds = !showAds }) { Text(if (showAds) "Reklamları gizle (${ads.size})" else "${ads.size} reklam gizlendi · göster") }
            }
            if (showAds) items(ads.size, key = { "ad-" + ads[it].url }) { i ->
                val m = ads[i]
                Text("Reklam · ${Format.fileNameFromUrl(m.url)} · ${Format.host(m.url)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(m) }.padding(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabManagerScreen(active: Tab, onClose: () -> Unit) {
    val tabs by TabManager.tabs.collectAsState()
    var incog by remember { mutableStateOf(active.incognito) }
    val shown = tabs.filter { it.incognito == incog }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("${tabs.size} sekme") }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Geri") } })
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(onClick = { TabManager.newTab(null, incog); onClose() }, icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(if (incog) "Yeni gizli sekme" else "Yeni sekme") }, shape = RoundedCornerShape(20.dp))
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    SegmentedButton(!incog, { incog = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sekmeler · ${tabs.count { !it.incognito }}") }
                    SegmentedButton(incog, { incog = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Gizli · ${tabs.count { it.incognito }}") }
                }
                LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shown, key = { it.id }) { t -> TabCard(t, t == active, onSelect = { TabManager.select(t); onClose() }, onClose = { TabManager.close(t) }) }
                }
            }
        }
    }
}

@Composable
private fun TabCard(t: Tab, active: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val title by t.title.collectAsState()
    val url by t.url.collectAsState()
    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerLow).border(if (active) 3.dp else 1.dp, if (active) c.primary else c.outlineVariant, RoundedCornerShape(20.dp)).clickable(onClick = onSelect)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (t.incognito) c.secondary else c.primary))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Sekmeyi kapat", Modifier.size(18.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(120.dp).background(c.surfaceContainerHigh).padding(12.dp), contentAlignment = Alignment.BottomStart) {
            Text(Format.host(url).ifEmpty { "Açılış sayfası" }, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 2)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == "bookmarks") "Yer imleri" else "Geçmiş") },
        text = {
            LazyColumn {
                if (list.isEmpty()) item { Text(if (kind == "bookmarks") "Henüz yer imi yok. Sayfa menüsünden ekleyebilirsin." else "Geçmiş boş.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(list) { (t, u) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(u) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (kind == "bookmarks") IconButton(onClick = { scope.launch { dao.removeBookmark(u) } }) { Icon(Icons.Rounded.Close, "Yer imini sil") }
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

private fun attachWebView(holder: FrameLayout, w: WebView) {
    if (w.parent === holder) return
    (w.parent as? ViewGroup)?.removeView(w)
    holder.removeAllViews()
    holder.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
}

private fun subLang(m: DetectedMedia) = Lang.normalize(m.lang).ifEmpty { Lang.fromFileName(m.url) }

/** Sitelerin gizlemek için verdiği adlar (master.txt, video.png) yerine kaynağın ne olduğu. */
private fun sourceDesc(m: DetectedMedia): String {
    val host = Format.host(m.url)
    val t = m.manifestText
    val d = if (m.kind == "hls" && t != null) {
        if (Hls.isMaster(t)) Regex("#EXT-X-STREAM-INF").findAll(t).count().let { n -> if (n > 1) "Ana liste · $n kalite" else "Ana liste" } else "Video listesi"
    } else when (m.kind) {
        "hls", "dash" -> "Video akışı"
        else -> Format.fileNameFromUrl(m.url)
    }
    return listOf(host, d).filter { it.isNotBlank() }.joinToString(" · ")
}
