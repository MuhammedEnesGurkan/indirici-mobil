package com.movietrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.OutputStore
import com.movietrack.app.util.Category
import com.movietrack.app.util.EpisodeParser
import com.movietrack.app.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun iconFor(cat: String): ImageVector = when (cat) {
    Category.VIDEO -> Icons.Default.Movie
    Category.MUSIC -> Icons.Default.MusicNote
    Category.IMAGE -> Icons.Default.Image
    Category.DOC -> Icons.Default.Description
    Category.ARCHIVE -> Icons.Default.Archive
    Category.APP -> Icons.Default.Apps
    else -> Icons.Default.InsertDriveFile
}

/** Medya kütüphanesi: tamamlanan indirmeler, türe göre kategoriler ve dizi/sezon/bölüm gruplama. */
@Composable
fun LibraryScreen() {
    val all by App.instance.downloads.all.collectAsState(initial = emptyList())
    val done = all.filter { it.status == Status.DONE }
    var cat by remember { mutableStateOf("Tümü") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("date") }
    var sortMenu by remember { mutableStateOf(false) }

    val filtered = done.filter { (cat == "Tümü" || cat == "Diziler" || it.category == cat) && (query.isBlank() || it.fileName.contains(query, true)) }
        .let { l -> when (sort) { "name" -> l.sortedBy { it.fileName.lowercase() }; "size" -> l.sortedByDescending { it.totalBytes }; else -> l.sortedByDescending { it.finishedAt } } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kütüphane", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${done.size} dosya · ${Format.bytes(done.sumOf { it.totalBytes })}", color = MT.Muted, fontSize = 12.sp)
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "Sırala") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf("date" to "Tarihe göre", "name" to "Ada göre", "size" to "Boyuta göre").forEach { (k, l) ->
                        DropdownMenuItem({ Text((if (sort == k) "✓ " else "") + l) }, { sort = k; sortMenu = false })
                    }
                }
            }
        }
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Dosya ara") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Tümü", "Diziler") + Category.ALL).forEach { c ->
                val n = when (c) { "Tümü" -> done.size; "Diziler" -> done.count { EpisodeParser.parse(it.fileName).isEpisode }; else -> done.count { it.category == c } }
                if (n > 0 || c == "Tümü") FilterChip(cat == c, { cat = c }, label = { Text("$c ($n)") })
            }
        }
        if (filtered.isEmpty()) Text("Henüz tamamlanan indirme yok.", color = MT.Muted, modifier = Modifier.padding(24.dp))
        if (cat == "Diziler") SeriesList(filtered) else LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 24.dp)) {
            items(filtered, key = { it.id }) { LibraryRow(it) }
        }
    }
}

/** Dosya adlarından dizi → sezon → bölüm gruplama (ör. "Breaking Bad - S02E05.mp4"). */
@Composable
private fun SeriesList(items: List<DownloadEntity>) {
    val groups = items.mapNotNull { e -> EpisodeParser.parse(e.fileName).takeIf { it.isEpisode }?.let { it to e } }
        .groupBy { it.first.series.ifBlank { "Bilinmeyen dizi" } }
    LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 24.dp)) {
        groups.toSortedMap().forEach { (series, eps) ->
            item(key = "h-$series") {
                Text(series, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Text("${eps.size} bölüm", color = MT.Muted, fontSize = 12.sp)
            }
            eps.sortedWith(compareBy({ it.first.season ?: 0 }, { it.first.episode ?: 0 })).forEach { (info, e) ->
                item(key = e.id) { LibraryRow(e, "S${(info.season ?: 1).toString().padStart(2, '0')}E${(info.episode ?: 0).toString().padStart(2, '0')}") }
            }
        }
    }
}

@Composable
private fun LibraryRow(e: DownloadEntity, prefix: String? = null) {
    val ctx = LocalContext.current
    val m = App.instance.downloads
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(MT.Surface, RoundedCornerShape(12.dp)).clickable { Open.open(ctx, e) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(MT.Surface2, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(iconFor(e.category), null, tint = MT.Accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text((prefix?.let { "$it · " } ?: "") + e.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(listOf(Format.bytes(e.totalBytes), SimpleDateFormat("d MMM yyyy", Locale("tr")).format(Date(e.finishedAt))).joinToString(" · "), color = MT.Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { e.labels.split(' ').filter { it.startsWith("aud:") || it.startsWith("subtitle:") }.forEach { LangTag(it) } }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Diğer") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Aç") }, { menu = false; Open.open(ctx, e) })
                DropdownMenuItem({ Text("Paylaş") }, { menu = false; Open.share(ctx, e) })
                DropdownMenuItem({ Text("Yeniden adlandır") }, { menu = false; rename = true })
                DropdownMenuItem({ Text("Sil") }, { menu = false; delete = true })
            }
        }
    }
    if (rename) {
        var name by remember { mutableStateOf(e.fileName) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = MT.Surface, title = { Text("Yeniden adlandır") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                rename = false
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val safe = Format.safeName(name)
                    if (e.outputUri != null && OutputStore.rename(ctx, e.outputUri!!, safe)) { e.fileName = safe; App.instance.db.downloads().update(e) }
                }
            }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Vazgeç") } })
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, containerColor = MT.Surface, title = { Text("Silinsin mi?") }, text = { Text(e.fileName) },
        confirmButton = { TextButton(onClick = { delete = false; m.remove(e.id, true) }) { Text("Sil", color = MT.Danger) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Vazgeç") } })
}
