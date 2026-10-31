package com.movietrack.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movietrack.app.App
import com.movietrack.app.BuildConfig
import com.movietrack.app.browser.TabManager

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MT.Surface, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = MT.Accent)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            desc?.let { Text(it, color = MT.Muted, fontSize = 12.sp) }
        }
        Switch(value, onChange)
    }
}

@Composable
fun ProfileScreen() {
    val s = App.instance.settings
    val v by s.version.collectAsState() // ayarlar değişince yeniden çiz
    val ctx = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            s.folderUri = uri.toString()
        }
    }
    if (v == Int.MIN_VALUE) return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profil ve ayarlar", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp))

        Section("Movie Track hesabı") {
            Text("İzleme listesi, dizi/bölüm takibi ve cihazlar arası senkronizasyon 2. aşamada bu hesapla bağlanacak.", color = MT.Muted, fontSize = 13.sp)
        }

        Section("İndirme") {
            Text("Dosya başına bağlantı (parça): ${s.parts}")
            Slider(s.parts.toFloat(), { s.parts = it.toInt() }, valueRange = 1f..16f, steps = 14)
            Text("Aynı anda en fazla indirme: ${s.maxConcurrent}")
            Slider(s.maxConcurrent.toFloat(), { s.maxConcurrent = it.toInt() }, valueRange = 1f..6f, steps = 4)
            SwitchRow("Yalnızca Wi-Fi", "Mobil veride indirmeler bekletilir", s.wifiOnly) { s.wifiOnly = it; App.instance.downloads.schedule() }
            SwitchRow("Panodaki bağlantıyı algıla", "Kopyaladığın bağlantı için indirme önerir", s.clipboardDetect) { s.clipboardDetect = it }
            Text("Kayıt klasörü", color = MT.Muted, fontSize = 12.sp)
            Text(if (s.folderUri != null) "Seçilen klasör" else "İndirilenler/MovieTrack")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickFolder.launch(null) }) { Text("Klasör seç") }
                if (s.folderUri != null) TextButton(onClick = { s.folderUri = null }) { Text("Varsayılana dön") }
            }
        }

        Section("Video akışları (M3U8 / MPD)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.outputMode == "mp4", { s.outputMode = "mp4" }, label = { Text("Birleşik MP4") })
                FilterChip(s.outputMode == "separate", { s.outputMode = "separate" }, label = { Text("Ayrı dosyalar") })
            }
            SwitchRow("Reklam aralarını atla", "SCTE-35 işaretli ve farklı sunucudan gelen kısa bölümler", s.skipAds) { s.skipAds = it }
            var a by remember { mutableStateOf(s.audioLangs) }
            OutlinedTextField(a, { a = it; s.audioLangs = it }, label = { Text("Tercih edilen ses dilleri (aud:)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var b by remember { mutableStateOf(s.subLangs) }
            OutlinedTextField(b, { b = it; s.subLangs = it }, label = { Text("Tercih edilen altyazı dilleri (subtitle:)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Section("Tarayıcı") {
            SwitchRow("Reklam ve izleyici engelleme", null, s.adBlock) { s.adBlock = it }
            SwitchRow("Pop-up engelleme", "Dokunmadan açılan pencereleri engeller", s.popupBlock) { s.popupBlock = it; TabManager.applySettings() }
            SwitchRow("Masaüstü sitesi", null, s.desktopMode) { s.desktopMode = it; TabManager.applySettings() }
            var engine by remember { mutableStateOf(s.searchEngine) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Google" to "https://www.google.com/search?q=", "DuckDuckGo" to "https://duckduckgo.com/?q=", "Bing" to "https://www.bing.com/search?q=").forEach { (n, u) ->
                    FilterChip(engine == u, { engine = u; s.searchEngine = u }, label = { Text(n) })
                }
            }
            TextButton(onClick = { TabManager.clearData(); cleared = true }) { Text(if (cleared) "Temizlendi ✓" else "Önbellek, çerez ve geçmişi temizle") }
        }

        Text("Movie Track ${BuildConfig.VERSION_NAME} · DRM korumalı içerikler indirilemez.", color = MT.Muted, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
    }
}

@Composable
fun WatchlistScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("İzleme listesi", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Section("2. aşamada geliyor") {
            Text("Movie Track hesabınla giriş yapınca izleme listen, takip ettiğin diziler, sezon/bölüm durumu ve yeni bölüm bildirimleri burada olacak. " +
                "İndirdiğin bölümler (ör. \"Dizi - S02E05\") otomatik eşleşip izlendi olarak işaretlenebilecek.", color = MT.Muted)
        }
    }
}
