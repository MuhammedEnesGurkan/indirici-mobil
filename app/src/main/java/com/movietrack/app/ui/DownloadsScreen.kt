package com.movietrack.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.LiveProgress
import com.movietrack.app.download.NewDownload
import com.movietrack.app.util.Format
import kotlinx.coroutines.launch

private val STATUS_TEXT = mapOf(
    Status.QUEUED to "Sırada", Status.RUNNING to "İndiriliyor", Status.PAUSED to "Duraklatıldı", Status.MERGING to "Birleştiriliyor",
    Status.DONE to "Tamamlandı", Status.ERROR to "Hata", Status.WAITING_WIFI to "Wi-Fi bekleniyor",
)

@Composable
fun DownloadsScreen(onAdd: () -> Unit) {
    val m = App.instance.downloads
    val all by m.all.collectAsState(initial = emptyList())
    val live by m.live.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf<DownloadEntity?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // TXT dosyasından bağlantı içe aktarma (1DM özelliği)
    val importTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@launch
            Regex("https?://\\S+").findAll(text).map { it.value }.distinct().forEach { u ->
                m.add(NewDownload(url = u, fileName = Format.fileNameFromUrl(u)))
            }
        }
    }

    val list = all.filter {
        when (filter) {
            "active" -> it.status in Status.ACTIVE || it.status == Status.PAUSED
            "done" -> it.status == Status.DONE
            "error" -> it.status == Status.ERROR
            else -> true
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("İndirmeler", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menü") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Tümünü duraklat") }, { menu = false; m.pauseAll() })
                        DropdownMenuItem({ Text("Tümünü sürdür") }, { menu = false; m.resumeAll() })
                        DropdownMenuItem({ Text("TXT'den bağlantı içe aktar") }, { menu = false; importTxt.launch(arrayOf("text/*")) })
                        DropdownMenuItem({ Text("Tamamlananları listeden kaldır") }, { menu = false; m.clearFinished() })
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Tümü", "active" to "Etkin", "done" to "Tamamlanan", "error" to "Hatalı").forEach { (k, l) ->
                    FilterChip(filter == k, { filter = k }, label = { Text(l) })
                }
            }
            if (list.isEmpty()) {
                Text("İndirme yok. Keşfet'te bir sitede videoyu açın ya da + ile bağlantı ekleyin. Kopyaladığınız bağlantılar otomatik algılanır.",
                    color = MT.Muted, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 90.dp)) {
                items(list, key = { it.id }) { e -> DownloadRow(e, live[e.id], onRefresh = { refresh = e }) }
            }
        }
        FloatingActionButton(onClick = onAdd, containerColor = MT.Accent, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.Add, "Bağlantı ekle") }
    }
    refresh?.let { e ->
        var u by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { refresh = null }, containerColor = MT.Surface,
            title = { Text("Bağlantıyı yenile") },
            text = { Column { Text("Süresi dolan bağlantının yerine yenisini yapıştırın; indirilen kısım korunur.", color = MT.Muted, fontSize = 13.sp); OutlinedTextField(u, { u = it }, singleLine = true, label = { Text("Yeni bağlantı") }) } },
            confirmButton = { TextButton(enabled = u.startsWith("http"), onClick = { m.refreshUrl(e.id, u); refresh = null }) { Text("Devam et") } },
            dismissButton = { TextButton(onClick = { refresh = null }) { Text("Vazgeç") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(e: DownloadEntity, p: LiveProgress?, onRefresh: () -> Unit) {
    val m = App.instance.downloads
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bytes = p?.bytes ?: e.downloadedBytes
    val total = p?.total ?: e.totalBytes
    val frac = when {
        e.status == Status.DONE -> 1f
        p != null && p.segTotal > 0 -> p.segDone.toFloat() / p.segTotal
        total > 0 -> (bytes.toFloat() / total).coerceIn(0f, 1f)
        else -> 0f
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(MT.Surface, RoundedCornerShape(12.dp)).clickable { if (e.status == Status.DONE) Open.open(ctx, e) }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.fileName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(e.category.ifEmpty { "Dosya" })
                    if (e.type != "direct") Tag(if (e.type == "hls") "M3U8" else "MPD", MT.Accent)
                    e.labels.split(' ').filter { it.isNotBlank() }.forEach { LangTag(it) }
                }
            }
            when (e.status) {
                Status.RUNNING, Status.QUEUED, Status.WAITING_WIFI -> IconButton(onClick = { m.pause(e.id) }) { Icon(Icons.Default.Pause, "Duraklat") }
                Status.PAUSED -> IconButton(onClick = { m.resume(e.id) }) { Icon(Icons.Default.PlayArrow, "Devam") }
                Status.ERROR -> IconButton(onClick = { m.resume(e.id) }) { Icon(Icons.Default.Refresh, "Yeniden dene") }
                Status.DONE -> IconButton(onClick = { Open.open(ctx, e) }) { Icon(Icons.Default.FolderOpen, "Aç") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Diğer") }
                DropdownMenu(menu, { menu = false }) {
                    if (e.status == Status.DONE) {
                        DropdownMenuItem({ Text("Aç") }, { menu = false; Open.open(ctx, e) })
                        DropdownMenuItem({ Text("Dosyayı paylaş") }, { menu = false; Open.share(ctx, e) })
                    } else DropdownMenuItem({ Text("Bağlantıyı yenile") }, { menu = false; onRefresh() })
                    DropdownMenuItem({ Text("Bağlantıyı paylaş") }, { menu = false; Open.shareLink(ctx, e.url) })
                    DropdownMenuItem({ Text("Listeden kaldır") }, { menu = false; m.remove(e.id, false) })
                    DropdownMenuItem({ Text("Sil (dosyayla)") }, { menu = false; confirmDelete = true })
                }
            }
        }
        if (e.status != Status.DONE) LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
            color = when (e.status) { Status.ERROR -> MT.Danger; Status.PAUSED -> MT.Ad; else -> MT.Accent }, trackColor = MT.Surface2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(STATUS_TEXT[e.status] ?: e.status, color = if (e.status == Status.ERROR) MT.Danger else if (e.status == Status.DONE) MT.Ok else MT.Muted, fontSize = 12.sp)
            Text(Format.bytes(bytes) + if (total > 0 && e.status != Status.DONE) " / ${Format.bytes(total)}" else "", color = MT.Muted, fontSize = 12.sp)
            if (p != null && p.speed > 0) Text(Format.speed(p.speed), color = MT.Muted, fontSize = 12.sp)
            if (p != null && p.etaSec > 0) Text("kalan ${Format.duration(p.etaSec)}", color = MT.Muted, fontSize = 12.sp)
            if (p != null && p.segTotal > 0) Text("${p.segDone}/${p.segTotal}", color = MT.Muted, fontSize = 12.sp)
        }
        p?.phase?.takeIf { it != "İndiriliyor" && e.status != Status.DONE }?.let { Text(it, color = MT.Muted, fontSize = 12.sp) }
        e.error?.let { Text(it, color = if (e.status == Status.ERROR) MT.Danger else MT.Ad, fontSize = 12.sp) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = MT.Surface,
        title = { Text("Silinsin mi?") }, text = { Text("${e.fileName} ve indirilen dosya silinecek.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; m.remove(e.id, true) }) { Text("Sil", color = MT.Danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } })
}
