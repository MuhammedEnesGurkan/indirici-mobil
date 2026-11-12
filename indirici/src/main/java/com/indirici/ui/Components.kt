package com.indirici.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.download.LiveProgress
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format

// ---------- Durum ----------

enum class Kind { QUEUED, CONNECTING, DOWNLOADING, PAUSED, MERGING, DONE, ERROR, NETWORK, EXPIRED }

data class StatusSpec(val kind: Kind, val label: String, val icon: ImageVector)

/** Durum her zaman ikon + metin + renkle anlatılır. */
fun statusOf(e: DownloadEntity, p: LiveProgress?): StatusSpec = when (e.status) {
    Status.QUEUED -> StatusSpec(Kind.QUEUED, "Sırada", Icons.Rounded.Schedule)
    Status.RUNNING -> if ((p?.bytes ?: 0L) == 0L && (p?.speed ?: 0L) == 0L && (p?.segDone ?: 0) == 0)
        StatusSpec(Kind.CONNECTING, "Bağlanıyor", Icons.Rounded.Sync)
    else StatusSpec(Kind.DOWNLOADING, "İndiriliyor", Icons.Rounded.ArrowDownward)
    Status.MERGING -> StatusSpec(Kind.MERGING, "Birleştiriliyor", Icons.Rounded.MergeType)
    Status.PAUSED -> StatusSpec(Kind.PAUSED, "Duraklatıldı", Icons.Rounded.PauseCircle)
    Status.DONE -> StatusSpec(Kind.DONE, "Tamamlandı", Icons.Rounded.CheckCircle)
    Status.WAITING_WIFI -> StatusSpec(Kind.NETWORK, "Ağ bekleniyor", Icons.Rounded.WifiOff)
    else -> if (isExpired(e)) StatusSpec(Kind.EXPIRED, "Link süresi doldu", Icons.Rounded.LinkOff)
    else StatusSpec(Kind.ERROR, "Hata", Icons.Rounded.Error)
}

/** Tamamlandı ama uyarıyla (ör. ses yok): kartta gösterilir, dokununca ayrıntı açılır. */
fun hasWarning(e: DownloadEntity) = e.status == Status.DONE && e.error?.startsWith("Uyarı") == true

/** Sunucunun bağlantıyı reddetmesi (imzalı linkin süresi dolmuş olabilir). */
fun isExpired(e: DownloadEntity): Boolean {
    val m = e.error ?: return false
    return Regex("\\b(401|403|404|410)\\b").containsMatchIn(m) || m.contains("süresi", true) || m.contains("expired", true)
}

@Composable
fun Kind.color(): Color {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Kind.QUEUED -> c.outline
        Kind.CONNECTING, Kind.DOWNLOADING, Kind.MERGING -> c.primary
        Kind.PAUSED, Kind.NETWORK -> c.secondary
        Kind.DONE -> c.tertiary
        Kind.ERROR, Kind.EXPIRED -> c.error
    }
}

@Composable
fun StatusLabel(s: StatusSpec, modifier: Modifier = Modifier) {
    val col by animateColorAsState(s.kind.color(), label = "st")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(s.icon, null, Modifier.size(16.dp), tint = col)
        Text(s.label, style = MaterialTheme.typography.labelMedium, color = col)
    }
}

fun fraction(e: DownloadEntity, p: LiveProgress?): Float {
    val bytes = p?.bytes ?: e.downloadedBytes
    val total = p?.total ?: e.totalBytes
    return when {
        e.status == Status.DONE -> 1f
        p != null && p.segTotal > 0 -> p.segDone.toFloat() / p.segTotal
        total > 0 -> (bytes.toFloat() / total).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** "45,2 MB / 120 MB · 3,4 MB/sn · 22 sn kaldı" */
fun metaLine(e: DownloadEntity, p: LiveProgress?): String {
    val bytes = p?.bytes ?: e.downloadedBytes
    val total = p?.total ?: e.totalBytes
    val s = statusOf(e, p).kind
    return when (s) {
        Kind.DONE -> if (hasWarning(e)) "⚠ " + e.error!!.removePrefix("Uyarı: ").lineSequence().first().substringBefore(".")
            else listOf(Format.bytes(if (total > 0) total else bytes), Dates.short(e.finishedAt.takeIf { it > 0 } ?: e.createdAt)).joinToString(" · ")
        Kind.ERROR, Kind.EXPIRED -> listOfNotNull(e.error?.lineSequence()?.firstOrNull()?.take(60), sizePart(bytes, total)).joinToString(" · ")
        Kind.MERGING -> when (p?.phase) {
            "Ses yeniden ekleniyor" -> "İlk birleştirmede ses eksik çıktı; videonun kendi sesiyle yeniden birleştiriliyor"
            "Kaydediliyor" -> "İndirilenler klasörüne kaydediliyor"
            "Ses ayrıca kaydediliyor" -> "Ses ayrıca kaydediliyor"
            "Altyazılar indiriliyor" -> if ((p?.segTotal ?: 0) > 0) "Altyazılar indiriliyor · ${p?.segDone}/${p?.segTotal}" else "Altyazılar indiriliyor"
            else -> "Ses ve görüntü tek dosyada birleştiriliyor"
        }
        else -> listOfNotNull(
            sizePart(bytes, total),
            p?.speed?.takeIf { it > 0 && s == Kind.DOWNLOADING }?.let { speed(it) },
            p?.etaSec?.takeIf { it > 0 && s == Kind.DOWNLOADING }?.let { eta(it) },
            if (s == Kind.PAUSED) "kaldığı yerden sürer" else null,
            if (s == Kind.NETWORK) "bağlantı gelince devam eder" else null,
        ).joinToString(" · ")
    }
}

private fun sizePart(bytes: Long, total: Long) = when {
    total > 0 -> "${Format.bytes(bytes)} / ${Format.bytes(total)}"
    bytes > 0 -> Format.bytes(bytes)
    else -> null
}

fun speed(bps: Long) = "${Format.bytes(bps)}/sn"

fun eta(sec: Long): String = when {
    sec < 60 -> "$sec sn kaldı"
    sec < 3600 -> "${sec / 60} dk kaldı"
    else -> "${sec / 3600} sa ${(sec % 3600) / 60} dk kaldı"
}

object Dates {
    private val day = java.text.SimpleDateFormat("d MMM", java.util.Locale("tr"))
    private val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
    fun short(ms: Long): String {
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        now.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val yesterday = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> "bugün ${time.format(ms)}"
            yesterday -> "dün ${time.format(ms)}"
            else -> day.format(ms)
        }
    }
}

/** Uzun dosya adını ortadan kırpar; uzantı görünür kalır. */
fun middleEllipsis(s: String, max: Int = 38): String {
    if (s.length <= max) return s
    val tail = (max * 0.4).toInt()
    return s.take(max - tail - 1) + "…" + s.takeLast(tail)
}

// ---------- İlerleme göstergeleri ----------

/** M3 Expressive tarzı dalgalı ilerleme: dolan kısım dalga, kalan kısım düz iz + bitiş noktası. */
@Composable
fun WavyProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
                 track: Color = MaterialTheme.colorScheme.surfaceContainerHighest, height: Dp = 10.dp, animate: Boolean = true) {
    val phase = if (animate) {
        val t = rememberInfiniteTransition(label = "wave")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase").value
    } else 0f
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        val stroke = 3.dp.toPx()
        val gap = 4.dp.toPx()
        val end = (w * progress.coerceIn(0f, 1f))
        val wl = 16.dp.toPx()
        val amp = (h - stroke) / 2 * 0.85f
        if (end > 0f) {
            val path = Path()
            var x = -wl * phase
            path.moveTo(x, mid)
            while (x < end + wl) {
                path.quadraticBezierTo(x + wl / 4, mid - amp * 2, x + wl / 2, mid)
                path.quadraticBezierTo(x + wl * 3 / 4, mid + amp * 2, x + wl, mid)
                x += wl
            }
            clipRect(0f, 0f, end, h) { drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round)) }
        }
        val start = if (end > 0f) end + gap else 0f
        if (start < w) {
            val th = 4.dp.toPx()
            drawRoundRect(track, Offset(start, mid - th / 2), Size(w - start, th), CornerRadius(th / 2))
            drawCircle(color, th / 2, Offset(w - th / 2, mid))
        }
    }
}

/** Düz ilerleme çubuğu (duraklatılmış, hatalı, sırada). */
@Composable
fun FlatProgress(progress: Float, color: Color, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest, thickness: Dp = 4.dp) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val th = thickness.toPx()
        val mid = size.height / 2
        val end = size.width * progress.coerceIn(0f, 1f)
        val gap = 4.dp.toPx()
        if (end > 0) drawRoundRect(color, Offset(0f, mid - th / 2), Size(end, th), CornerRadius(th / 2))
        val s = if (end > 0) end + gap else 0f
        if (s < size.width) drawRoundRect(track, Offset(s, mid - th / 2), Size(size.width - s, th), CornerRadius(th / 2))
    }
}

/** Belirsiz süre (bağlanma, birleştirme). */
@Composable
fun IndeterminateProgress(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition(label = "ind")
    val x by t.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val th = 4.dp.toPx()
        val mid = size.height / 2
        drawRoundRect(track, Offset(0f, mid - th / 2), Size(size.width, th), CornerRadius(th / 2))
        val s = (x * size.width).coerceAtLeast(0f)
        val e = ((x + 0.35f) * size.width).coerceAtMost(size.width)
        if (e > s) drawRoundRect(color, Offset(s, mid - th / 2), Size(e - s, th), CornerRadius(th / 2))
    }
}

@Composable
fun DownloadProgress(e: DownloadEntity, p: LiveProgress?, modifier: Modifier = Modifier) {
    val s = statusOf(e, p)
    when (s.kind) {
        Kind.DOWNLOADING -> WavyProgress(fraction(e, p), modifier)
        Kind.MERGING -> if ((p?.segTotal ?: 0) > 0) WavyProgress(fraction(e, p), modifier) else IndeterminateProgress(modifier)
        Kind.CONNECTING -> IndeterminateProgress(modifier)
        Kind.DONE -> {}
        else -> FlatProgress(fraction(e, p), s.kind.color(), modifier)
    }
}

/** IDM'nin imzası: her bağlantının indirdiği parça ayrı bir şerit. `parts` = "başlangıç,bitiş,inen;..." */
@Composable
fun ConnectionRibbon(parts: List<Triple<Long, Long, Long>>, color: Color, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val total = parts.sumOf { (it.second - it.first + 1).coerceAtLeast(1) }.coerceAtLeast(1)
        parts.forEach { (s, e, d) ->
            val len = (e - s + 1).coerceAtLeast(1)
            Box(Modifier.weight(len.toFloat() / total).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(track)) {
                Box(Modifier.fillMaxWidth((d.toFloat() / len).coerceIn(0f, 1f)).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(color))
            }
        }
    }
}

fun parseParts(s: String): List<Triple<Long, Long, Long>> = s.split(';').mapNotNull { p ->
    val a = p.split(',').mapNotNull { it.trim().toLongOrNull() }
    if (a.size >= 3) Triple(a[0], a[1], a[2]) else null
}

// ---------- Küçük resim / tür ----------

fun categoryIcon(cat: String): ImageVector = when (cat) {
    Category.VIDEO -> Icons.Rounded.Movie
    Category.MUSIC -> Icons.Rounded.MusicNote
    Category.DOC -> Icons.Rounded.Description
    Category.APP -> Icons.Rounded.Android
    Category.ARCHIVE -> Icons.Rounded.FolderZip
    Category.IMAGE -> Icons.Rounded.Image
    else -> Icons.Rounded.InsertDriveFile
}

@Composable
fun categoryColors(cat: String): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (cat) {
        Category.VIDEO -> c.primaryContainer to c.onPrimaryContainer
        Category.MUSIC -> c.tertiaryContainer to c.onTertiaryContainer
        Category.DOC -> c.secondaryContainer to c.onSecondaryContainer
        Category.APP -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

@Composable
fun FileThumb(cat: String, size: Dp = 48.dp, selected: Boolean = false, radius: Dp = 14.dp) {
    val (bg, fg) = if (selected) MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary else categoryColors(cat)
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(bg), contentAlignment = Alignment.Center) {
        Icon(if (selected) Icons.Rounded.Check else categoryIcon(cat), null, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

fun categoryOf(e: DownloadEntity) = e.category.ifEmpty { Category.of(e.fileName, e.mime) }

// ---------- İndirme kartı ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadCard(
    e: DownloadEntity, p: LiveProgress?, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onOpen: () -> Unit, onRefreshLink: () -> Unit, modifier: Modifier = Modifier,
) {
    val s = statusOf(e, p)
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, label = "bg")
    val meta = metaLine(e, p)
    val pct = (fraction(e, p) * 100).toInt()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = "${e.fileName}, ${s.label}, $meta" }
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(categoryOf(e), selected = selected)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(middleEllipsis(e.fileName), style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.kind != Kind.DONE) DownloadProgress(e, p, Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(s, Modifier.weight(1f))
                if (s.kind != Kind.DONE && pct > 0) Text("%$pct", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        QuickAction(s.kind, onPause, onResume, onOpen, onRefreshLink)
    }
}

/** Kartta tek hızlı eylem. */
@Composable
fun QuickAction(k: Kind, onPause: () -> Unit, onResume: () -> Unit, onOpen: () -> Unit, onRefreshLink: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    when (k) {
        Kind.DOWNLOADING, Kind.CONNECTING, Kind.QUEUED, Kind.NETWORK ->
            FilledTonalIconButton(onPause, Modifier.padding(4.dp), shape = shape) { Icon(Icons.Rounded.Pause, "Duraklat") }
        Kind.PAUSED -> FilledTonalIconButton(onResume, Modifier.padding(4.dp), shape = shape) { Icon(Icons.Rounded.PlayArrow, "Devam et") }
        Kind.ERROR -> FilledTonalIconButton(onResume, Modifier.padding(4.dp), shape = shape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(c.errorContainer, c.onErrorContainer)) { Icon(Icons.Rounded.Refresh, "Yeniden dene") }
        Kind.EXPIRED -> FilledTonalIconButton(onRefreshLink, Modifier.padding(4.dp), shape = shape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(c.errorContainer, c.onErrorContainer)) { Icon(Icons.Rounded.LinkOff, "Linki yenile") }
        Kind.DONE -> FilledTonalIconButton(onOpen, Modifier.padding(4.dp), shape = shape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(c.tertiaryContainer, c.onTertiaryContainer)) { Icon(Icons.Rounded.OpenInNew, "Aç") }
        Kind.MERGING -> IconButton(onPause, Modifier.padding(4.dp)) { Icon(Icons.Rounded.Close, "Durdur") }
    }
}

// ---------- Boş durum ----------

@Composable
fun EmptyState(
    icon: ImageVector, badge: ImageVector?, title: String, body: String, modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    primary: Pair<String, () -> Unit>? = null, primaryIcon: ImageVector? = null, tonal: Boolean = false,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(168.dp).padding(bottom = 12.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(52.dp)).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(72.dp))
            }
            if (badge != null) Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(badge, null, tint = c.onTertiaryContainer, modifier = Modifier.size(28.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        primary?.let { (label, on) ->
            val content: @Composable () -> Unit = {
                primaryIcon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                Text(label)
            }
            if (tonal) FilledTonalButton(on, Modifier.height(48.dp)) { content() } else Button(on, Modifier.height(48.dp)) { content() }
        }
        secondary?.let { (label, on) -> TextButton(on, Modifier.height(48.dp)) { Text(label) } }
    }
}

/** Dikey boşluk kısaltması. */
@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Küçük yuvarlak renkli nokta (lejant). */
@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

