package com.indirici.ui

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.movietrack.app.App
import com.movietrack.app.BuildConfig
import com.movietrack.app.browser.TabManager
import com.movietrack.app.data.Status
import com.movietrack.app.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenQueue: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val app = App.instance
    val v1 by app.settings.version.collectAsState()
    val v2 by app.ui.version.collectAsState()
    (v1 + v2).hashCode()
    BackHandler(page != null) { page = null }
    when (page) {
        null -> Main { if (it == "queue") onOpenQueue() else page = it }
        else -> Sub(page!!, onBack = { page = null })
    }
}

private data class Group(val key: String, val icon: ImageVector, val title: String, val sub: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(onOpen: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val bar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val groups = listOf(
        Group("general", Icons.Rounded.Tune, "Genel", "Pano algılama, oynatıcı"),
        Group("downloads", Icons.Rounded.Download, "İndirmeler", "Klasör, hız, aynı anda indirme, diller"),
        Group("queue", Icons.Rounded.Schedule, "Sıra ve zamanlayıcı", "Başlama sırası, saat aralığı, şarj"),
        Group("browser", Icons.Rounded.Public, "Tarayıcı", "Arama motoru, reklam engelleme, masaüstü sitesi"),
        Group("look", Icons.Rounded.Palette, "Görünüm", "Tema, tam siyah, duvar kağıdı renkleri"),
        Group("about", Icons.Rounded.Info, "Hakkında", "Sürüm ${BuildConfig.VERSION_NAME}"),
    )
    Scaffold(Modifier.nestedScroll(bar.nestedScrollConnection), contentWindowInsets = WindowInsets(0),
        topBar = { LargeTopAppBar(title = { Text("Ayarlar") }, scrollBehavior = bar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageCard()
            Column(Modifier.clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { g ->
                    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow).clickable { onOpen(g.key) }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(g.icon, null, tint = c.onSurfaceVariant) }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(g.title, style = MaterialTheme.typography.labelLarge)
                            Text(g.sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StorageCard() {
    val c = MaterialTheme.colorScheme
    val all by App.instance.downloads.all.collectAsState(initial = emptyList())
    val mine = all.filter { it.status == Status.DONE }.sumOf { it.totalBytes.coerceAtLeast(it.downloadedBytes) }
    val st = remember { try { StatFs(Environment.getExternalStorageDirectory().path) } catch (_: Exception) { null } }
    val total = st?.totalBytes ?: 0L
    val free = st?.availableBytes ?: 0L
    val other = (total - free - mine).coerceAtLeast(0L)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text("Depolama", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (total > 0) Text("${Format.bytes(total)}'ın ${Format.bytes(total - free)}'ı dolu", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        if (total > 0) Row(Modifier.fillMaxWidth().height(16.dp).semantics { contentDescription = "indirici ${Format.bytes(mine)}, diğer ${Format.bytes(other)}, boş ${Format.bytes(free)}" },
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(mine to c.primary, other to c.secondaryContainer, free to c.surfaceContainerHighest).forEach { (b, col) ->
                if (b > 0) Box(Modifier.weight(b.toFloat().coerceAtLeast(total * 0.01f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(col))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(c.primary, "indirici ${Format.bytes(mine)}")
            Legend(c.secondaryContainer, "Diğer")
            Legend(c.surfaceContainerHighest, "Boş ${Format.bytes(free)}")
        }
    }
}

@Composable
private fun Legend(col: androidx.compose.ui.graphics.Color, t: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(col))
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sub(page: String, onBack: () -> Unit) {
    val title = mapOf("general" to "Genel", "downloads" to "İndirmeler", "browser" to "Tarayıcı", "look" to "Görünüm", "about" to "Hakkında")[page] ?: ""
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Geri") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (page) {
                "general" -> GeneralPage()
                "downloads" -> DownloadsPage()
                "browser" -> BrowserPage()
                "look" -> LookPage()
                else -> AboutPage()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
private fun Item(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchItem(title: String, sub: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).toggleable(value, enabled, Role.Switch, onChange)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, null, enabled = enabled)
    }
}

@Composable
private fun GeneralPage() {
    val s = App.instance.settings
    Section("Pano") {
        SwitchItem("Panodaki bağlantıyı algıla", "Bir bağlantı kopyaladığında indirmeyi önerir", s.clipboardDetect) { s.clipboardDetect = it }
    }
    Section("Oynatıcı") {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
            Text("İleri/geri sarma adımı", style = MaterialTheme.typography.labelLarge)
            Text("Çift dokunma ve ileri/geri düğmeleri bu kadar sarar; art arda dokununca toplanır", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val steps = com.movietrack.app.player.PlayerActivity.SEEK_STEPS
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                steps.forEachIndexed { i, sec ->
                    SegmentedButton(selected = s.seekStepSec == sec, onClick = { s.seekStepSec = sec }, shape = SegmentedButtonDefaults.itemShape(i, steps.size)) { Text("$sec sn") }
                }
            }
        }
    }
}

@Composable
private fun DownloadsPage() {
    val s = App.instance.settings
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        s.folderUri = uri.toString()
    }
    Section("Kayıt") {
        Item("Varsayılan klasör", if (s.folderUri != null) "Seçilen klasör" else "İndirilenler/indirici", onClick = { pick.launch(null) }) {
            if (s.folderUri != null) TextButton(onClick = { s.folderUri = null }) { Text("Varsayılan") } else Icon(Icons.Rounded.ChevronRight, null)
        }
    }
    Section("Hız ve bağlantı") {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
            Row { Text("Dosya başına bağlantı", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); Text("${s.parts}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            Slider(s.parts.toFloat(), { s.parts = it.toInt() }, valueRange = 1f..16f, steps = 14)
            Text("Dosya aynı anda bu kadar parçada iner. Daha fazlası her sunucuda hızlandırmaz.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Item("Aynı anda en fazla", "Diğerleri sırada bekler") {
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { s.maxConcurrent = s.maxConcurrent - 1; App.instance.downloads.schedule() }) { Icon(Icons.Rounded.Remove, "Azalt") }
                Text("${s.maxConcurrent}", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { s.maxConcurrent = s.maxConcurrent + 1; App.instance.downloads.schedule() }) { Icon(Icons.Rounded.Add, "Artır") }
            }
        }
        SwitchItem("Sadece Wi-Fi", "Mobil veride indirmeler sırada bekler", s.wifiOnly) { s.wifiOnly = it; App.instance.downloads.schedule() }
    }
    Section("Video akışları") {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Çıktı", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(s.outputMode == "mp4", { s.outputMode = "mp4" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Tek MP4") }
                SegmentedButton(s.outputMode == "separate", { s.outputMode = "separate" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Ayrı dosyalar") }
            }
        }
        SwitchItem("Reklam aralarını atla", "İşaretli ve farklı sunucudan gelen kısa bölümler", s.skipAds) { s.skipAds = it }
        LangField("Tercih edilen ses dilleri", s.audioLangs) { s.audioLangs = it }
        LangField("Tercih edilen altyazı dilleri", s.subLangs) { s.subLangs = it }
    }
}

@Composable
private fun LangField(label: String, value: String, onChange: (String) -> Unit) {
    var t by remember { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
        OutlinedTextField(t, { t = it; onChange(it.replace(" ", "")) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Virgülle ayır: tr,en") }, shape = RoundedCornerShape(12.dp))
    }
}

@Composable
private fun BrowserPage() {
    val s = App.instance.settings
    Section("Arama") {
        val engines = listOf("Google" to "https://www.google.com/search?q=", "DuckDuckGo" to "https://duckduckgo.com/?q=", "Bing" to "https://www.bing.com/search?q=")
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Arama motoru", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                engines.forEachIndexed { i, (n, u) -> SegmentedButton(s.searchEngine == u, { s.searchEngine = u }, SegmentedButtonDefaults.itemShape(i, engines.size)) { Text(n) } }
            }
        }
    }
    Section("Görünüm") {
        val ui = App.instance.ui
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adres çubuğu", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!ui.addressBarTop, { ui.addressBarTop = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Altta") }
                SegmentedButton(ui.addressBarTop, { ui.addressBarTop = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Üstte") }
            }
            Text("Altta tek elle kullanım kolay; üstte klasik tarayıcı düzeni.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Section("Sayfalar") {
        SwitchItem("Reklam ve izleyici engelleme", null, s.adBlock) { s.adBlock = it }
        SwitchItem("Açılır pencere engelleme", "Dokunmadan açılan pencereleri engeller", s.popupBlock) { s.popupBlock = it; TabManager.applySettings() }
        SwitchItem("Masaüstü sitesi iste", null, s.desktopMode) { s.desktopMode = it; TabManager.applySettings() }
    }
}

@Composable
private fun LookPage() {
    val ui = App.instance.ui
    val dark = isDarkUi(ui)
    Section("Tema") {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp)) {
            val opts = listOf(Triple("system", "Sistem", Icons.Rounded.BrightnessAuto), Triple("light", "Açık", Icons.Rounded.LightMode), Triple("dark", "Koyu", Icons.Rounded.DarkMode))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opts.forEachIndexed { i, (k, n, ic) ->
                    SegmentedButton(ui.theme == k, { ui.theme = k }, SegmentedButtonDefaults.itemShape(i, opts.size), icon = { SegmentedButtonDefaults.Icon(ui.theme == k) { Icon(ic, null, Modifier.size(18.dp)) } }) { Text(n) }
                }
            }
        }
        SwitchItem("Tam siyah", if (dark) "AMOLED ekranda pil tasarrufu" else "Koyu temada geçerli", ui.amoled) { ui.amoled = it }
        SwitchItem("Duvar kağıdı renkleri", if (Build.VERSION.SDK_INT >= 31) "Renkleri duvar kağıdından al" else "Android 12 ve üstünde", ui.dynamicColor, enabled = Build.VERSION.SDK_INT >= 31) { ui.dynamicColor = it }
    }
    Section("Renk") {
        val enabled = !(ui.dynamicColor && Build.VERSION.SDK_INT >= 31)
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SEEDS.forEachIndexed { i, (name, col) ->
                val on = ui.seed == i
                val preview = if (i == 0) (if (dark) DarkColors else LightColors) else seedScheme(col, dark)
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp))
                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(26.dp)).padding(5.dp) else Modifier)
                    .clip(RoundedCornerShape(26.dp)).background(preview.primary)
                    .selectable(on, enabled = enabled, role = Role.RadioButton) { ui.seed = i }
                    .semantics { contentDescription = name },
                    contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.5f).background(preview.tertiaryContainer))
                }
            }
        }
        if (!enabled) Item("Duvar kağıdı renkleri açık", "Renk seçmek için önce onu kapat")
    }
    Section("Önizleme") {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(com.movietrack.app.util.Category.VIDEO)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("ornek-video.mp4", style = MaterialTheme.typography.titleSmall)
                Text("45,2 MB / 120 MB · 3,4 MB/sn · 22 sn kaldı", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WavyProgress(0.38f, Modifier.padding(top = 6.dp))
            }
            FilledTonalIconButton({}, Modifier.padding(start = 4.dp)) { Icon(Icons.Rounded.Download, null) }
        }
    }
}

@Composable
private fun AboutPage() {
    Section("indirici") {
        Item("Sürüm", BuildConfig.VERSION_NAME)
        Item("Reklamsız", "İndirme yöneticisi; Movie Track'in indirme motorunu kullanır.")
    }
}

