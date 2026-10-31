package com.movietrack.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import com.movietrack.app.browser.TabManager
import com.movietrack.app.ui.AddDownloadDialog
import com.movietrack.app.ui.BrowserScreen
import com.movietrack.app.ui.DownloadsScreen
import com.movietrack.app.ui.LibraryScreen
import com.movietrack.app.ui.MT
import com.movietrack.app.ui.MovieTrackTheme
import com.movietrack.app.ui.ProfileScreen
import com.movietrack.app.ui.WatchlistScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val tabState = MutableStateFlow("discover")
    private val pendingUrl = MutableStateFlow<String?>(null) // "URL ekle" iletişim kutusuna gidecek bağlantı
    private val clipboardUrl = MutableStateFlow<String?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TabManager.attach(this)
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
        handleIntent(intent)
        setContent { MovieTrackTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("tab")?.let { tabState.value = it }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                Regex("https?://\\S+").find(text)?.value?.let { pendingUrl.value = it }
            }
            Intent.ACTION_VIEW -> i.dataString?.let { if (it.startsWith("http")) { tabState.value = "discover"; TabManager.newTab(it) } }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !App.instance.settings.clipboardDetect) return
        // Akıllı indirme: panoya kopyalanan bağlantıyı algıla
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null } ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        val s = App.instance.settings
        if (url != s.lastClipboard) { s.lastClipboard = url; clipboardUrl.value = url }
    }

    @Composable
    private fun Root() {
        val tab by tabState.collectAsState()
        val pending by pendingUrl.collectAsState()
        val clip by clipboardUrl.collectAsState()
        val live by App.instance.downloads.live.collectAsState()
        val snack = remember { SnackbarHostState() }
        var addDialog by remember { mutableStateOf<String?>(null) }
        var crash by remember { mutableStateOf(java.io.File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()) }

        LaunchedEffect(pending) { pending?.let { addDialog = it; pendingUrl.value = null } }
        LaunchedEffect(clip) {
            val u = clip ?: return@LaunchedEffect
            clipboardUrl.value = null
            val r = snack.showSnackbar("Kopyalanan bağlantı: ${u.take(50)}", actionLabel = "İndir", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) addDialog = u
        }

        Scaffold(
            containerColor = MT.Bg,
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar(containerColor = MT.Bg) {
                    val colors = NavigationBarItemDefaults.colors(selectedIconColor = MT.Accent, selectedTextColor = MT.Text, unselectedIconColor = MT.Muted, unselectedTextColor = MT.Muted, indicatorColor = MT.Surface2)
                    NavigationBarItem(tab == "discover", { tabState.value = "discover" }, { Icon(Icons.Outlined.Explore, null) }, label = { Text("Keşfet") }, colors = colors)
                    NavigationBarItem(tab == "watchlist", { tabState.value = "watchlist" }, { Icon(Icons.Outlined.BookmarkBorder, null) }, label = { Text("İzleme") }, colors = colors)
                    NavigationBarItem(tab == "downloads", { tabState.value = "downloads" }, {
                        BadgedBox(badge = { if (live.isNotEmpty()) Badge { Text("${live.size}") } }) { Icon(Icons.Outlined.Download, null) }
                    }, label = { Text("İndirmeler") }, colors = colors)
                    NavigationBarItem(tab == "library", { tabState.value = "library" }, { Icon(Icons.Outlined.VideoLibrary, null) }, label = { Text("Kütüphane") }, colors = colors)
                    NavigationBarItem(tab == "profile", { tabState.value = "profile" }, { Icon(Icons.Outlined.Person, null) }, label = { Text("Profil") }, colors = colors)
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    "discover" -> BrowserScreen(onAddUrl = { addDialog = it }, snack = snack)
                    "watchlist" -> WatchlistScreen()
                    "downloads" -> DownloadsScreen(onAdd = { addDialog = "" })
                    "library" -> LibraryScreen()
                    else -> ProfileScreen()
                }
            }
        }
        crash?.let { text ->
            val close = { java.io.File(filesDir, "last_crash.txt").delete(); crash = null }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { close() }, containerColor = MT.Surface,
                title = { Text("Uygulama son açılışta çöktü") },
                text = {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Text("Aşağıdaki hata kaydını kopyalayıp geliştiriciye gönderin.", color = MT.Muted)
                        Text(text.take(4000), fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                        App.instance.settings.lastClipboard = "crash"
                        close()
                    }) { Text("Kopyala") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { close() }) { Text("Kapat") } },
            )
        }
        addDialog?.let { u -> AddDownloadDialog(initialUrl = u, referer = null, pageTitle = null, onDismiss = { addDialog = null }, onQueued = { addDialog = null; tabState.value = "downloads" }) }
    }
}
