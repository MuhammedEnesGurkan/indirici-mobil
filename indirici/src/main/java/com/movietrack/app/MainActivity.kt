package com.movietrack.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.indirici.ui.BrowserScreen
import com.indirici.ui.DetailScreen
import com.indirici.ui.DownloadRequest
import com.indirici.ui.DownloadsScreen
import com.indirici.ui.FilesScreen
import com.indirici.ui.IndiriciTheme
import com.indirici.ui.NewDownloadSheet
import com.indirici.ui.Onboarding
import com.indirici.ui.SettingsScreen
import com.indirici.ui.isDarkUi
import com.indirici.ui.middleEllipsis
import com.movietrack.app.browser.TabManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * indirici ana ekranı. Ortak arka uç bildirimden açılırken bu sınıfı "tab" ekstrasıyla çağırır.
 */
class MainActivity : ComponentActivity() {
    private val tabState = MutableStateFlow("downloads")
    private val pending = MutableStateFlow<DownloadRequest?>(null)
    private val clipboardUrl = MutableStateFlow<String?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TabManager.attach(this)
        if (App.instance.ui.onboarded && Build.VERSION.SDK_INT <= 28) permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        handleIntent(intent)
        setContent {
            val ui = App.instance.ui
            IndiriciTheme(ui) {
                val dark = isDarkUi(ui)
                LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                }
                val v by ui.version.collectAsState()
                v.hashCode()
                if (!ui.onboarded) Onboarding(onDone = {
                    ui.onboarded = true
                    if (Build.VERSION.SDK_INT <= 28) permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }) else Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // Yalnızca hata ayıklama derlemesinde: CI ekran görüntüleri için karşılamayı atla
        if (BuildConfig.DEBUG && i.getBooleanExtra("skip_onboarding", false)) App.instance.ui.onboarded = true
        i.getStringExtra("tab")?.let { tabState.value = if (it == "discover") "browser" else it }
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
                Regex("https?://\\S+").find(text)?.value?.let { pending.value = DownloadRequest(url = it) }
            }
            Intent.ACTION_VIEW -> i.dataString?.let { if (it.startsWith("http")) { tabState.value = "browser"; TabManager.newTab(it) } }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !App.instance.settings.clipboardDetect) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null } ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        val s = App.instance.settings
        if (url != s.lastClipboard) { s.lastClipboard = url; clipboardUrl.value = url }
    }

    private data class Dest(val key: String, val label: String, val on: ImageVector, val off: ImageVector)

    @Composable
    private fun Root() {
        val tab by tabState.collectAsState()
        val req by pending.collectAsState()
        val clip by clipboardUrl.collectAsState()
        val live by App.instance.downloads.live.collectAsState()
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var detail by remember { mutableStateOf<Long?>(null) }
        var crash by remember { mutableStateOf(java.io.File(filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()) }

        LaunchedEffect(clip) {
            val u = clip ?: return@LaunchedEffect
            clipboardUrl.value = null
            val r = snack.showSnackbar("Panodaki bağlantı algılandı: ${middleEllipsis(u, 40)}", actionLabel = "İndir", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) pending.value = DownloadRequest(url = u, fromClipboard = true)
        }
        val newDownload: (DownloadRequest) -> Unit = { pending.value = it }

        val d = detail
        if (d != null) {
            DetailScreen(d, onBack = { detail = null })
        } else {
            val dests = listOf(
                Dest("downloads", "İndirmeler", Icons.Rounded.Download, Icons.Outlined.Download),
                Dest("browser", "Tarayıcı", Icons.Rounded.Public, Icons.Outlined.Public),
                Dest("files", "Dosyalar", Icons.Rounded.Folder, Icons.Outlined.Folder),
                Dest("settings", "Ayarlar", Icons.Rounded.Settings, Icons.Outlined.Settings),
            )
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = { SnackbarHost(snack) },
                bottomBar = {
                    NavigationBar {
                        dests.forEach { x ->
                            NavigationBarItem(selected = tab == x.key, onClick = { tabState.value = x.key }, label = { Text(x.label) }, icon = {
                                if (x.key == "downloads" && live.isNotEmpty()) BadgedBox(badge = { Badge { Text("${live.size}") } }) { Icon(if (tab == x.key) x.on else x.off, null) }
                                else Icon(if (tab == x.key) x.on else x.off, null)
                            })
                        }
                    }
                },
            ) { pad ->
                AnimatedContent(tab, Modifier.fillMaxSize().padding(pad), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        "browser" -> Box(Modifier.statusBarsPadding()) { BrowserScreen(snack, onDownload = newDownload) }
                        "files" -> FilesScreen(onNew = newDownload, onOpenDownloads = { tabState.value = "downloads" })
                        "settings" -> SettingsScreen()
                        else -> DownloadsScreen(snack, onNew = newDownload, onOpenDetail = { detail = it }, onOpenBrowser = { tabState.value = "browser" })
                    }
                }
            }
        }

        req?.let { r ->
            NewDownloadSheet(r, onDismiss = { pending.value = null }, onQueued = { msg ->
                pending.value = null
                if (tab != "browser") tabState.value = "downloads"
                scope.launch { snack.showSnackbar(msg) }
            })
        }

        crash?.let { text ->
            val close = { java.io.File(filesDir, "last_crash.txt").delete(); crash = null }
            AlertDialog(
                onDismissRequest = { close() },
                title = { Text("Uygulama son açılışta kapandı") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Hata kaydını kopyalayıp geliştiriciye gönderebilirsin.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text.take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", text))
                        App.instance.settings.lastClipboard = "crash"
                        close()
                    }) { Text("Kopyala") }
                },
                dismissButton = { TextButton(onClick = { close() }) { Text("Kapat") } },
            )
        }
    }
}
