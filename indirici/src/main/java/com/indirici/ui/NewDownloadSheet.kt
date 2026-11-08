package com.indirici.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movietrack.app.App
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.OutputStore
import com.movietrack.app.download.Requests
import com.movietrack.app.util.Category
import com.movietrack.app.util.Format
import com.movietrack.app.util.Lang
import kotlinx.coroutines.launch

/** İndirme isteği: elle yapıştırılan bağlantı ya da tarayıcıda algılanan medya. */
data class DownloadRequest(
    val url: String = "",
    val kind: String? = null,
    val referer: String? = null,
    val pageTitle: String? = null,
    val manifest: String? = null,
    val userAgent: String? = null,
    val name: String? = null,
    val preselect: String? = null,
    val pageSubtitles: List<Pair<String, String>> = emptyList(),
    val inline: Map<String, String> = emptyMap(),
    val fromClipboard: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewDownloadSheet(req: DownloadRequest, onDismiss: () -> Unit, onQueued: (String) -> Unit) {
    val app = App.instance
    val s = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf(req.url) }
    var fromClip by remember { mutableStateOf(req.fromClipboard) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Analyzer.Info?>(null) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf<String?>(null) }
    var recommended by remember { mutableStateOf<String?>(null) }
    val audios = remember { mutableStateListOf<String>() }
    val subs = remember { mutableStateListOf<String>() }
    var mode by remember { mutableStateOf(s.outputMode) }
    var skipAds by remember { mutableStateOf(s.skipAds) }
    var audioOnly by remember { mutableStateOf(false) }
    var alsoAudio by remember { mutableStateOf(false) }
    var referer by remember { mutableStateOf(req.referer ?: "") }
    var duplicate by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf(false) }
    var parts by remember { mutableIntStateOf(s.parts) }

    fun analyze() {
        val u = url.trim()
        if (!u.startsWith("http")) { error = "Bağlantı http:// ya da https:// ile başlamalı."; return }
        loading = true; error = null; info = null
        scope.launch {
            try {
                var i = Analyzer.analyze(u, req.kind, referer.ifBlank { null }, req.userAgent, req.manifest, req.inline)
                if (i.kind != "direct") {
                    val extra = req.pageSubtitles.filter { (su, _) -> i.subtitles.none { it.id == su } }
                        .map { (su, l) -> Analyzer.Track(su, l, Format.fileNameFromUrl(su), Lang.subLabel(l)) }
                    if (extra.isNotEmpty()) i = i.copy(subtitles = i.subtitles + extra)
                }
                val (base, f) = Analyzer.naming(req.pageTitle, i.fileName.substringBeforeLast('.').ifBlank { Format.fileNameFromUrl(u).substringBeforeLast('.') }, req.referer)
                name = req.name ?: if (i.kind == "direct") (if (req.pageTitle != null && Analyzer.categoryFolder(i.fileName, i.mime) == "Video") "$base.${Format.extOf(i.fileName).ifEmpty { "mp4" }}" else i.fileName) else base
                folder = if (i.kind == "direct") Analyzer.categoryFolder(i.fileName, i.mime).let { c -> if (c == "Video" && req.pageTitle != null) f else c } else f
                // Önerilen: sesi olan en yüksek kalite
                recommended = (if (i.audios.isEmpty()) i.qualities.firstOrNull { it.audio != false } else null)?.id ?: i.qualities.firstOrNull()?.id
                quality = i.qualities.firstOrNull { it.id == req.preselect }?.id ?: recommended
                val wantA = s.audioLangs.split(',').map { Lang.normalize(it) }
                audios.clear(); audios += i.audios.filter { it.lang in wantA }.map { it.id }.ifEmpty { i.audios.filter { it.isDefault }.map { it.id }.ifEmpty { i.audios.take(1).map { it.id } } }
                val wantS = s.subLangs.split(',').map { Lang.normalize(it) }
                subs.clear(); subs += i.subtitles.filter { it.lang in wantS }.map { it.id }
                duplicate = app.downloads.existing(u) != null
                info = i
            } catch (t: Throwable) {
                error = "Bağlantı açılamadı: " + (t.message ?: t.javaClass.simpleName)
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (req.url.isNotBlank()) analyze() }

    fun queue(now: Boolean) {
        val i = info ?: return
        scope.launch {
            if (parts != s.parts) s.parts = parts
            // Yalnızca ses ve ayrı ses izi yoksa ses görüntü akışından ayıklanır: en küçük kalite yeter
            val q = if (audioOnly && i.audios.isEmpty()) i.qualities.lastOrNull { it.audio != false }?.id ?: quality else quality
            val r = Requests.build(i, url.trim(), name.trim(), folder, q, audios.toList(), subs.toList(), mode, skipAds,
                referer.ifBlank { null }, req.userAgent, req.pageTitle, req.inline,
                audioOnly = audioOnly, alsoAudio = alsoAudio && !audioOnly)
            val id = app.downloads.add(r)
            if (!now) app.downloads.pause(id)
            onQueued(if (now) "İndirme başladı" else "Sıraya eklendi, duraklatılmış bekliyor")
        }
    }

    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            Text(if (req.kind != null || req.pageTitle != null) "İndir" else "Yeni indirme", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; fromClip = false }, label = { Text("Bağlantı") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    supportingText = { Text(if (fromClip) "Panodan dolduruldu" else "Dosya, video sayfası, M3U8 ya da MPD bağlantısı") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { analyze() }),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { clipText(ctx)?.let { url = it; fromClip = true; analyze() } }) { Icon(Icons.Rounded.ContentPaste, "Panodan yapıştır") }
                            if (url.isNotEmpty()) IconButton(onClick = { url = ""; info = null; error = null }) { Icon(Icons.Rounded.Cancel, "Temizle") }
                        }
                    },
                )
                if (info == null && !loading && url.isNotBlank()) TextButton(onClick = { analyze() }) { Text("Bağlantıyı çözümle") }
                if (loading) Skeleton()
                error?.let { WarningCard(it) }
                info?.let { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FileThumb(if (i.kind == "direct") Category.of(i.fileName, i.mime) else Category.VIDEO, size = 56.dp, radius = 16.dp)
                        OutlinedTextField(name, { name = it }, label = { Text("Dosya adı") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Verified, null, tint = c.tertiary, modifier = Modifier.size(18.dp))
                        Text(describe(i), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                    if (i.drm) WarningCard("Bu içerik DRM ile korunuyor; indirilemez.")
                    if (i.live) WarningCard("Canlı yayın kaydı henüz desteklenmiyor.")
                    if (duplicate) Text("Bu bağlantıyı daha önce indirdin.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Column {
                        Text("Kayıt yeri", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        FilterChip(selected = false, onClick = { editFolder = true }, label = { Text(OutputStore.displayFolder(folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 40.dp))
                    }
                    if (i.kind != "direct") SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!audioOnly, { audioOnly = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Video") }
                        SegmentedButton(audioOnly, { audioOnly = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sadece ses") }
                    }
                    if (audioOnly) Text(if (i.audios.isNotEmpty()) "Yalnızca ses izi iner; görüntü indirilmez. Çıktı: .m4a"
                        else "Ses görüntü akışının içinde; en küçük kalite indirilip ses ayıklanır. Çıktı: .m4a",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    if (i.qualities.size > 1 && !audioOnly) QualityList(i, quality, recommended) { quality = it }
                    if (i.audios.isNotEmpty()) ChipGroup("Ses dili", i.audios.map { it.id to "${Lang.displayName(it.lang).takeIf { n -> n != "Bilinmeyen dil" } ?: it.name}" }, audios)
                    if (i.subtitles.isNotEmpty()) ChipGroup("Altyazı", i.subtitles.map { it.id to "${Lang.displayName(it.lang).takeIf { n -> n != "Bilinmeyen dil" } ?: it.name}" }, subs)
                    if (!audioOnly && noAudio(i, quality, audios)) WarningCard(
                        if (i.audios.isEmpty()) "Bu kalitede ses yok, video sessiz iner. Başka bir kalite seç ya da sayfadaki ana listeyi aç."
                        else "Hiç ses dili seçilmedi, video sessiz iner.")
                }
                // Gelişmiş: teknik ayarlar yalnızca burada
                Column {
                    HorizontalDivider(color = c.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = c.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Gelişmiş", style = MaterialTheme.typography.labelLarge)
                            Text("Bağlantı sayısı, çıktı biçimi, referer", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        }
                        Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (advanced) "Gizle" else "Göster", tint = c.onSurfaceVariant)
                    }
                    AnimatedVisibility(advanced) {
                        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Eş zamanlı bağlantı", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Text("$parts", style = MaterialTheme.typography.titleMedium, color = c.primary)
                            }
                            Slider(parts.toFloat(), { parts = it.toInt() }, valueRange = 1f..16f, steps = 14)
                            Text("Tek dosyalık indirmelerde geçerli; ayar tüm indirmeler için saklanır.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            if (info?.kind != null && info?.kind != "direct") {
                                Text("Çıktı", style = MaterialTheme.typography.labelLarge)
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    SegmentedButton(mode == "mp4", { mode = "mp4" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Tek MP4") }
                                    SegmentedButton(mode == "separate", { mode = "separate" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Ayrı dosyalar") }
                                }
                                if (!audioOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Sesi ayrıca da kaydet", style = MaterialTheme.typography.labelLarge)
                                        Text("Videonun yanına .m4a ses dosyası", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                    }
                                    Switch(alsoAudio, { alsoAudio = it })
                                }
                                if ((info?.adBreaks ?: 0) > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Reklam aralarını atla (${info?.adBreaks})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    Switch(skipAds, { skipAds = it })
                                }
                            }
                            OutlinedTextField(referer, { referer = it }, label = { Text("Referer") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = c.outlineVariant)
            val ok = info != null && info?.drm == false && info?.live == false && name.isNotBlank() && !loading
            Row(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { queue(false) }, enabled = ok, modifier = Modifier.weight(1f).height(48.dp)) { Text("Sıraya ekle") }
                Button(onClick = { queue(true) }, enabled = ok, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (info?.let { noAudio(it, quality, audios) } == true) "Yine de indir" else "Şimdi indir", maxLines = 1, softWrap = false)
                }
            }
        }
    }

    if (editFolder) {
        var f by remember { mutableStateOf(folder) }
        AlertDialog(onDismissRequest = { editFolder = false }, title = { Text("Kayıt yeri") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ana indirme klasörünün içindeki alt klasör. Ana klasörü Ayarlar › İndirmeler'den değiştirebilirsin.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(f, { f = it }, singleLine = true, label = { Text("Alt klasör") })
                }
            },
            confirmButton = { TextButton(onClick = { folder = f.trim(); editFolder = false }) { Text("Kaydet") } },
            dismissButton = { TextButton(onClick = { editFolder = false }) { Text("Vazgeç") } })
    }
}

private fun describe(i: Analyzer.Info): String = buildList {
    when (i.kind) {
        "hls" -> add("Video akışı")
        "dash" -> add("Video akışı (DASH)")
        "youtube" -> add("YouTube videosu")
        else -> add(Category.of(i.fileName, i.mime).let { if (it == Category.OTHER) "Dosya" else it })
    }
    if (i.duration > 0) add(Format.duration(i.duration.toLong()))
    if (i.size > 0) add(Format.bytes(i.size))
    if (i.qualities.size > 1) add("${i.qualities.size} kalite")
    if (i.kind == "direct") add("yarıda kalırsa devam eder")
}.joinToString(" · ")

@Composable
private fun QualityList(i: Analyzer.Info, selected: String?, recommended: String?, onPick: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Kalite", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        i.qualities.forEach { q ->
            val on = q.id == selected
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) c.secondaryContainer else c.surfaceContainerLow)
                    .selectable(on, role = Role.RadioButton) { onPick(q.id) }.heightIn(min = 56.dp).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(on, null, Modifier.padding(horizontal = 12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (q.height > 0) "${q.height}p" else q.label, style = MaterialTheme.typography.titleMedium)
                        if (q.id == recommended) Text("Önerilen", style = MaterialTheme.typography.labelSmall, color = c.onTertiaryContainer,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.tertiaryContainer).padding(horizontal = 8.dp, vertical = 2.dp))
                        if (q.audio == false && i.audios.isEmpty()) Text("Sessiz", style = MaterialTheme.typography.labelSmall, color = c.onErrorContainer,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.errorContainer).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                    if (q.height > 0 && q.label.isNotBlank() && q.label != "${q.height}p") Text(q.label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1)
                }
                if (q.estSize > 0) Text("~${Format.bytes(q.estSize)}", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, items: List<Pair<String, String>>, selected: MutableList<String>) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (id, label) ->
                val on = id in selected
                FilterChip(on, { if (on) selected.remove(id) else selected.add(id) }, label = { Text(label) },
                    leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null)
            }
        }
    }
}

@Composable
fun WarningCard(text: String) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.errorContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Warning, null, tint = c.onErrorContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onErrorContainer)
    }
}

@Composable
private fun Skeleton() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.alpha(a), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(bg))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(0.8f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(bg))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(8.dp)).background(bg))
            }
        }
        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(bg))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.TravelExplore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text("Bağlantı çözümleniyor…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seçime göre çıkacak dosyada ses olmayacak mı (yalnızca kesin bilindiğinde true). */
private fun noAudio(i: Analyzer.Info, quality: String?, audios: List<String>): Boolean {
    if (i.kind == "direct") return false
    if (audios.any { a -> i.audios.any { it.id == a } }) return false
    return i.qualities.firstOrNull { it.id == quality }?.audio == false
}

fun clipText(ctx: Context): String? = try {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { Regex("https?://\\S+").find(it)?.value }
} catch (_: Exception) { null }
