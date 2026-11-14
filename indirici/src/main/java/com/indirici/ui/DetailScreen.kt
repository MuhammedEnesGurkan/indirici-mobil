package com.indirici.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movietrack.app.App
import com.movietrack.app.data.Status
import com.movietrack.app.download.OutputStore
import com.movietrack.app.util.Format
import com.movietrack.app.util.KV
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val m = App.instance.downloads
    val all by m.all.collectAsState(initial = null)
    val live by m.live.collectAsState()
    val ctx = LocalContext.current
    val e = all?.firstOrNull { it.id == id }
    BackHandler(onBack = onBack)
    if (all != null && e == null) { LaunchedEffect(Unit) { onBack() }; return }
    e ?: return
    val p = live[e.id]
    val s = statusOf(e, p)
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }

    // Son 60 saniyenin hızı (her saniye bir örnek)
    val samples = remember { mutableStateListOf<Long>() }
    LaunchedEffect(e.id) {
        while (true) {
            val sp = m.live.value[e.id]?.speed ?: 0L
            samples.add(sp); if (samples.size > 60) samples.removeAt(0)
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Geri") } },
                actions = {
                    IconButton(onClick = { Open.shareLink(ctx, e.url) }) { Icon(Icons.Rounded.Share, "Bağlantıyı paylaş") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Diğer seçenekler") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Bağlantıyı kopyala") }, { menu = false; copyText(ctx, e.url) })
                            DropdownMenuItem({ Text("Linki yenile") }, { menu = false; refresh = true })
                            DropdownMenuItem({ Text("Listeden kaldır (dosya kalsın)") }, { menu = false; m.remove(e.id, false); onBack() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (label, icon, action) = when (s.kind) {
                    Kind.DOWNLOADING, Kind.CONNECTING, Kind.QUEUED, Kind.NETWORK, Kind.MERGING -> Triple("Duraklat", Icons.Rounded.Pause) { m.pause(e.id); Unit }
                    Kind.PAUSED -> Triple("Devam et", Icons.Rounded.PlayArrow) { m.resume(e.id); Unit }
                    Kind.ERROR -> Triple("Yeniden dene", Icons.Rounded.Refresh) { m.resume(e.id); Unit }
                    Kind.EXPIRED -> Triple("Linki yenile", Icons.Rounded.Link) { refresh = true }
                    Kind.DONE -> Triple("Aç", Icons.Rounded.OpenInNew) { Open.open(ctx, e) }
                }
                Button(onClick = action, modifier = Modifier.weight(1f).height(48.dp)) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label) }
                if (s.kind != Kind.EXPIRED && s.kind != Kind.DONE) FilledTonalIconButton({ refresh = true }, Modifier.size(48.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Link, "Linki yenile") }
                if (s.kind == Kind.DONE) FilledTonalIconButton({ Open.share(ctx, e) }, Modifier.size(48.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Share, "Dosyayı paylaş") }
                FilledTonalIconButton({ confirm = true }, Modifier.size(48.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Delete, "Sil") }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FileThumb(categoryOf(e), size = 72.dp, radius = 22.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.fileName, style = MaterialTheme.typography.titleMedium)
                    val (bg, fg) = when (s.kind) {
                        Kind.ERROR, Kind.EXPIRED -> c.errorContainer to c.onErrorContainer
                        Kind.DONE -> c.tertiaryContainer to c.onTertiaryContainer
                        Kind.PAUSED, Kind.NETWORK, Kind.QUEUED -> c.secondaryContainer to c.onSecondaryContainer
                        else -> c.primaryContainer to c.onPrimaryContainer
                    }
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(bg).heightIn(min = 32.dp).padding(start = 8.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(s.icon, null, tint = fg, modifier = Modifier.size(18.dp))
                        Text(s.label, style = MaterialTheme.typography.labelLarge, color = fg)
                    }
                }
            }

            val bytes = p?.bytes ?: e.downloadedBytes
            val total = p?.total ?: e.totalBytes
            val pct = (fraction(e, p) * 100).toInt()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$pct", fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp,
                        color = if (s.kind == Kind.EXPIRED || s.kind == Kind.ERROR) c.onSurfaceVariant else c.onSurface,
                        style = MaterialTheme.typography.displayLarge, modifier = Modifier.semantics { contentDescription = "Yüzde $pct" })
                    Text("%", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp).weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p?.speed?.takeIf { it > 0 }?.let { speed(it) } ?: "—", style = MaterialTheme.typography.titleMedium)
                        Text(p?.etaSec?.takeIf { it > 0 }?.let { eta(it) } ?: s.label.lowercase(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        p?.phase?.takeIf { it.startsWith("Sunucu yavaşlattı") }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
                    }
                }
                Text(if (total > 0) "${Format.bytes(bytes)} / ${Format.bytes(total)}" else Format.bytes(bytes), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                val parts = parseParts(e.parts)
                val segColor = when (s.kind) { Kind.DONE -> c.tertiary; Kind.ERROR, Kind.EXPIRED, Kind.PAUSED -> c.outline; else -> c.primary }
                if (parts.size > 1) {
                    Column {
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Text("Parçalar · ${parts.size} bağlantı", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("her şerit bir bağlantı", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                        }
                        ConnectionRibbon(parts, segColor, Modifier.semantics { contentDescription = "${parts.size} bağlantı, ${parts.count { it.third >= it.second - it.first + 1 }} tanesi bitti" })
                    }
                } else if (p != null && p.segTotal > 0) {
                    Column {
                        Text(if (s.kind == Kind.MERGING) metaLine(e, p) else "Parçalar · ${p.segDone} / ${p.segTotal}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        DownloadProgress(e, p)
                    }
                } else DownloadProgress(e, p)
            }

            if (hasWarning(e)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.errorContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.LinkOff, null, tint = c.onErrorContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.error ?: "", style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer)
                    TextButton(onClick = { copyText(ctx, diagnosticReport(e)) }) { Text("Ayrıntıyı kopyala") }
                }
            }
            if (s.kind == Kind.EXPIRED || s.kind == Kind.ERROR) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.errorContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (s.kind == Kind.EXPIRED) Icons.Rounded.LinkOff else s.icon, null, tint = c.onErrorContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (s.kind == Kind.EXPIRED) "Sunucu bu bağlantıyı artık kabul etmiyor. Videonun sayfasını yeniden açıp yeni bağlantıyı ver; indirme %$pct'den devam eder."
                        else (e.error ?: com.movietrack.app.download.Errors.SYSTEM_MESSAGE).let { m -> if (m.contains("kaldığı yerden")) m else "$m\nYeniden denersen kaldığı yerden devam eder." }, style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer)
                    TextButton(onClick = { copyText(ctx, diagnosticReport(e)) }) { Text("Ayrıntıyı kopyala") }
                }
            }

            if (s.kind == Kind.DOWNLOADING || s.kind == Kind.CONNECTING) SpeedChart(samples.toList())

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                InfoRow("Bağlantı", e.url, copy = { copyText(ctx, e.url) })
                InfoRow("Kayıt yeri", OutputStore.displayFolder(KV.decode(e.meta)["folder"] ?: ""))
                InfoRow("Başladı", Dates.short(e.createdAt))
                if (e.status == Status.DONE) {
                    val st = KV.decode(e.meta)
                    val bytes = e.downloadedBytes.coerceAtLeast(e.totalBytes)
                    if (e.finishedAt > e.createdAt) InfoRow("Toplam süre", dur(e.finishedAt - e.createdAt) + " (duraklatmalar dahil)")
                    st["st.dl"]?.toLongOrNull()?.takeIf { it > 0 }?.let { ms -> InfoRow("İndirme", "${dur(ms)} · ortalama ${speed(bytes * 1000 / ms)}") }
                    st["st.merge"]?.toLongOrNull()?.takeIf { it > 0 }?.let { ms -> InfoRow("Birleştirme", "${dur(ms)} · ${speed(bytes * 1000 / ms)}") }
                    if (bytes > 0) InfoRow("Boyut", Format.bytes(bytes))
                }
                KV.decode(e.meta)["diag"]?.let {
                    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tanı raporu", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            copyText(ctx, diagnosticReport(e))
                        }) { Text("Kopyala") }
                    }
                }
                if (e.status == Status.DONE) {
                    var tracks by remember(e.id) { mutableStateOf<String?>(null) }
                    LaunchedEffect(e.id) { tracks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { describeTracks(ctx, e) } }
                    tracks?.let { InfoRow("İzler", it) }
                }
                if (e.labels.isNotBlank()) InfoRow("Seçimler", e.labels.replace("aud:", "ses ").replace("subtitle:", "altyazı "))
                InfoRow("Devam ettirme", when {
                    e.type != "direct" -> "Parça parça sürer"
                    parseParts(e.parts).size > 1 -> "Destekleniyor, ${parseParts(e.parts).size} bağlantı"
                    else -> "Sunucuya bağlı"
                })
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (refresh) RefreshLinkDialog(e, onDismiss = { refresh = false })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Silinsin mi?") }, text = { Text("${e.fileName} ve indirilen dosya silinecek.") },
        confirmButton = { Button(onClick = { confirm = false; m.remove(e.id, true); onBack() }, colors = ButtonDefaults.buttonColors(c.error, c.onError)) { Text("Sil") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Vazgeç") } })
}

@Composable
private fun InfoRow(k: String, v: String, copy: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow).heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.widthIn(min = 112.dp, max = 112.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
        copy?.let { IconButton(onClick = it) { Icon(Icons.Rounded.ContentCopy, "Kopyala", Modifier.size(20.dp)) } }
    }
}

@Composable
private fun SpeedChart(values: List<Long>) {
    val c = MaterialTheme.colorScheme
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val avg = if (values.isEmpty()) 0L else values.sum() / values.size
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerLow).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
        Row {
            Text("Hız · son 60 sn", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("en yüksek ${speed(max)}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
        val line = c.primary
        val guide = c.outlineVariant
        Canvas(Modifier.fillMaxWidth().height(80.dp).padding(top = 6.dp).semantics { contentDescription = "Ortalama hız ${speed(avg)}" }) {
            val h = size.height
            val y = { v: Long -> h - (v.toFloat() / max) * (h - 6.dp.toPx()) - 3.dp.toPx() }
            drawLine(guide, Offset(0f, y(avg)), Offset(size.width, y(avg)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            if (values.size > 1) {
                val step = size.width / 59f
                val off = (60 - values.size) * step
                val path = Path()
                values.forEachIndexed { i, v -> val x = off + i * step; if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v)) }
                drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(line, 4.dp.toPx(), Offset(size.width, y(values.last())))
            }
        }
    }
}

/** Dosyadaki görüntü/ses izleri ve bu telefonda yerleşik çözücüsü olup olmadığı. */
private fun describeTracks(ctx: android.content.Context, e: com.movietrack.app.data.DownloadEntity): String = try {
    val uri = Open.contentUri(ctx, e.outputUri) ?: error("dosya yok")
    val ex = android.media.MediaExtractor()
    try {
        ex.setDataSource(ctx, uri, null)
        val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        (0 until ex.trackCount).mapNotNull { i ->
            val f = ex.getTrackFormat(i)
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: return@mapNotNull null
            val kind = when { mime.startsWith("video/") -> "görüntü"; mime.startsWith("audio/") -> "ses"; else -> return@mapNotNull null }
            val lang = f.getString(android.media.MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" }?.let { " $it" } ?: ""
            val ok = try { codecs.findDecoderForFormat(f) != null } catch (_: Exception) { true }
            "$kind ${mime.substringAfter('/')}$lang" + if (!ok) " (telefonda yerleşik çözücü yok; uygulamanın oynatıcısı çalar)" else ""
        }.joinToString(" · ").ifEmpty { "iz bulunamadı" }
    } finally { ex.release() }
} catch (t: Throwable) { "okunamadı" }

/** "1 sa 12 dk", "18 dk 20 sn", "45 sn" */
private fun dur(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec >= 3600 -> "${sec / 3600} sa ${(sec % 3600) / 60} dk"
        sec >= 60 -> "${sec / 60} dk ${sec % 60} sn"
        else -> "$sec sn"
    }
}
