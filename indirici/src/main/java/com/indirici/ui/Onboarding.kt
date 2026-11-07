package com.indirici.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.movietrack.app.App
import kotlinx.coroutines.launch

@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    Surface(color = c.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, null, tint = c.primary)
                Text("indirici", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onDone) { Text("Atla") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Slide("Her şeyi indir", "Bağlantıyı yapıştır ya da tarayıcıda gez. Video, müzik, belge: indirici bulur, sen kaliteyi seçersin.") { ArtAll() }
                    1 -> Slide("Hızlı ve kesintisiz", "Dosya parçalara bölünür, parçalar aynı anda iner. Bağlantı koparsa kaldığı yerden devam eder.") { ArtFast() }
                    2 -> Slide("Düzenli kal", "İnenler türüne göre kendiliğinden ayrılır. Aradığını kategoriden ya da aramadan bulursun.") { ArtTidy() }
                    else -> Permissions(onDone)
                }
            }
            if (pager.currentPage < 3) Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).semantics { contentDescription = "Sayfa ${pager.currentPage + 1} / 3" }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { i ->
                        val w by animateDpAsState(if (i == pager.currentPage) 28.dp else 8.dp, label = "dot")
                        Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) c.primary else c.outlineVariant))
                    }
                }
                Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, Modifier.height(48.dp)) {
                    Text("İleri"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowForward, null, Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Slide(title: String, body: String, art: @Composable BoxScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp).clip(RoundedCornerShape(40.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), content = art)
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, size: Dp, bg: Color, fg: Color, radius: Dp, x: Dp, y: Dp, align: Alignment, scope: BoxScope) = with(scope) {
    Box(Modifier.align(align).offset(x, y).size(size).clip(RoundedCornerShape(radius)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.45f))
    }
}

@Composable
private fun BoxScope.ArtAll() {
    val c = MaterialTheme.colorScheme
    Tile(Icons.Rounded.Download, 200.dp, c.primaryContainer, c.onPrimaryContainer, 100.dp, 0.dp, 0.dp, Alignment.Center, this)
    Tile(Icons.Rounded.Movie, 84.dp, c.tertiaryContainer, c.onTertiaryContainer, 28.dp, 24.dp, 32.dp, Alignment.TopStart, this)
    Tile(Icons.Rounded.MusicNote, 72.dp, c.secondaryContainer, c.onSecondaryContainer, 36.dp, (-24).dp, 56.dp, Alignment.TopEnd, this)
    Tile(Icons.Rounded.Description, 72.dp, c.surfaceContainerHighest, c.onSurfaceVariant, 22.dp, 40.dp, (-40).dp, Alignment.BottomStart, this)
    Tile(Icons.Rounded.Android, 92.dp, c.tertiaryContainer, c.onTertiaryContainer, 30.dp, (-32).dp, (-32).dp, Alignment.BottomEnd, this)
}

@Composable
private fun BoxScope.ArtFast() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = c.tertiary, modifier = Modifier.size(32.dp))
            Text("8,2 MB/sn", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 8.dp))
        }
        ConnectionRibbon(listOf(Triple(0L, 299L, 300L), Triple(300L, 499L, 160L), Triple(500L, 799L, 190L), Triple(800L, 999L, 184L)), c.primary, height = 28.dp)
        ConnectionRibbon(listOf(Triple(0L, 199L, 200L), Triple(200L, 499L, 165L), Triple(500L, 699L, 140L), Triple(700L, 999L, 90L)), c.primary, height = 28.dp)
        ConnectionRibbon(listOf(Triple(0L, 399L, 360L), Triple(400L, 699L, 135L), Triple(700L, 999L, 60L)), c.tertiary, height = 28.dp)
        Text("Her şerit, dosyanın aynı anda inen bir parçası.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

@Composable
private fun BoxScope.ArtTidy() {
    val c = MaterialTheme.colorScheme
    val tiles = listOf(
        Triple(Icons.Rounded.Movie, "Video", c.primaryContainer to c.onPrimaryContainer),
        Triple(Icons.Rounded.MusicNote, "Müzik", c.tertiaryContainer to c.onTertiaryContainer),
        Triple(Icons.Rounded.Description, "Belge", c.secondaryContainer to c.onSecondaryContainer),
        Triple(Icons.Rounded.Android, "Uygulama", c.surfaceContainerHighest to c.onSurface),
    )
    val shapes = listOf(RoundedCornerShape(28.dp), RoundedCornerShape(75.dp, 75.dp, 28.dp, 28.dp), RoundedCornerShape(28.dp, 28.dp, 75.dp, 28.dp), RoundedCornerShape(28.dp))
    Column(Modifier.align(Alignment.Center).padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { i, (ic, n, col) ->
                    Column(Modifier.weight(1f).height(140.dp).clip(shapes[r * 2 + i]).background(col.first).padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(ic, null, tint = col.second, modifier = Modifier.size(32.dp))
                        Text(n, style = MaterialTheme.typography.titleMedium, color = col.second)
                    }
                }
            }
        }
    }
}

@Composable
private fun Permissions(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val s = App.instance.settings
    fun granted() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(granted()) }
    var folder by remember { mutableStateOf(s.folderUri != null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it || granted() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
        s.folderUri = uri.toString(); folder = true
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Başlamadan iki izin", style = MaterialTheme.typography.headlineMedium)
            Text("İkisini de sonra Ayarlar'dan değiştirebilirsin.", style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PermCard(Icons.Rounded.Notifications, c.primaryContainer, c.onPrimaryContainer, "Bildirimler",
                "İndirme sürerken hızı ve kalan süreyi bildirimde görürsün; bitince haber veririz.", notif, "İzin ver") {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermCard(Icons.Rounded.FolderOpen, c.tertiaryContainer, c.onTertiaryContainer, "İndirme klasörü",
                "Seçmezsen dosyalar İndirilenler/indirici içine kaydedilir. Bir klasör seçersen yalnızca o klasörü görürüz.", folder, "Klasör seç") { pick.launch(null) }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(24.dp).height(48.dp)) { Text("Başla") }
    }
}

@Composable
private fun PermCard(icon: ImageVector, bg: Color, fg: Color, title: String, body: String, done: Boolean, action: String, onAction: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerLow).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Icon(icon, null, tint = fg) }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (done) StatusLabel(StatusSpec(Kind.DONE, "Tamam", Icons.Rounded.CheckCircle))
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (!done) FilledTonalButton(onClick = onAction, modifier = Modifier.height(48.dp)) { Text(action) }
    }
}
