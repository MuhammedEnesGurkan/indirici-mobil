package com.movietrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movietrack.app.App
import com.movietrack.app.download.Analyzer
import com.movietrack.app.download.NewDownload
import com.movietrack.app.util.Format
import com.movietrack.app.util.Lang
import kotlinx.coroutines.launch

@Composable
fun Tag(text: String, color: Color = MT.Muted) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun LangTag(label: String) = Tag(label, if (label.startsWith("aud:")) MT.Aud else if (label.startsWith("subtitle:")) MT.Sub else MT.Muted)

/**
 * Bir bağlantıyı indirme iletişim kutusu. Bağlantı verilmişse otomatik analiz eder.
 * @param kind tarayıcıdan gelen tür (hls/dash/video/...) — bilinmiyorsa uzantıdan tahmin edilir
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddDownloadDialog(
    initialUrl: String,
    referer: String?,
    pageTitle: String?,
    kind: String? = null,
    manifestText: String? = null,
    userAgent: String? = null,
    presetName: String? = null,
    preselectQuality: String? = null,
    pageSubtitles: List<Pair<String, String>> = emptyList(),
    inline: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
    onQueued: () -> Unit,
) {
    val app = App.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Analyzer.Info?>(null) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf<String?>(null) }
    val audios = remember { mutableStateListOf<String>() }
    val subs = remember { mutableStateListOf<String>() }
    var mode by remember { mutableStateOf(s.outputMode) }
    var skipAds by remember { mutableStateOf(s.skipAds) }
    var duplicate by remember { mutableStateOf(false) }

    fun analyze() {
        if (!url.startsWith("http")) { error = "Geçerli bir http(s) bağlantısı girin"; return }
        loading = true; error = null; info = null
        scope.launch {
            try {
                var i = Analyzer.analyze(url, kind, referer, userAgent, manifestText, inline)
                // Sayfada ayrıca bulunan altyazılar (ör. tr.vtt, en.vtt) akışa eklenebilir
                if (i.kind != "direct") {
                    val extra = pageSubtitles.filter { (u, _) -> i.subtitles.none { it.id == u } }
                        .map { (u, l) -> Analyzer.Track(u, l, Format.fileNameFromUrl(u), com.movietrack.app.util.Lang.subLabel(l)) }
                    if (extra.isNotEmpty()) i = i.copy(subtitles = i.subtitles + extra)
                }
                val (base, f) = Analyzer.naming(pageTitle, i.fileName.substringBeforeLast('.').ifBlank { Format.fileNameFromUrl(url).substringBeforeLast('.') }, referer)
                name = presetName ?: if (i.kind == "direct") (if (pageTitle != null && Analyzer.categoryFolder(i.fileName, i.mime) == "Video") "$base.${Format.extOf(i.fileName).ifEmpty { "mp4" }}" else i.fileName) else base
                folder = if (i.kind == "direct") Analyzer.categoryFolder(i.fileName, i.mime).let { c -> if (c == "Video" && pageTitle != null) f else c } else f
                quality = i.qualities.firstOrNull { it.id == preselectQuality }?.id ?: i.qualities.firstOrNull()?.id
                // Seçilen kalite sessizse ve ayrı ses izi yoksa sesli bir kaliteyi öne al
                if (i.audios.isEmpty() && i.qualities.firstOrNull { it.id == quality }?.audio == false)
                    i.qualities.firstOrNull { it.audio != false }?.let { quality = it.id }
                val wantA = s.audioLangs.split(',').map { Lang.normalize(it) }
                audios.clear(); audios += i.audios.filter { it.lang in wantA }.map { it.id }.ifEmpty { i.audios.filter { it.isDefault }.map { it.id }.ifEmpty { i.audios.take(1).map { it.id } } }
                val wantS = s.subLangs.split(',').map { Lang.normalize(it) }
                subs.clear(); subs += i.subtitles.filter { it.lang in wantS }.map { it.id }
                duplicate = app.downloads.existing(url) != null
                info = i
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (initialUrl.isNotBlank()) analyze() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MT.Surface,
        title = { Text(if (info?.kind == "hls" || info?.kind == "dash") "Akışı indir" else "İndir") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initialUrl.isBlank() || error != null) {
                    OutlinedTextField(url, { url = it }, label = { Text("Bağlantı (MP4, M3U8, MPD, dosya…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { analyze() }) { Text("Analiz et") }
                }
                if (loading) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 10.dp)); Text("Analiz ediliyor…", color = MT.Muted) }
                error?.let { Text(it, color = MT.Danger) }
                info?.let { i ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(i.kind.uppercase(), MT.Accent)
                        if (i.duration > 0) Tag("⏱ ${Format.duration(i.duration.toLong())}")
                        if (i.size > 0) Tag(Format.bytes(i.size))
                        if (i.adBreaks > 0) Tag("AD ×${i.adBreaks}", MT.Ad)
                        if (i.encrypted) Tag("AES")
                        if (i.live) Tag("CANLI", MT.Danger)
                        if (i.drm) Tag("DRM", MT.Danger)
                    }
                    if (i.drm) Text("Bu içerik DRM ile korunuyor; indirilemez.", color = MT.Danger)
                    if (i.live) Text("Canlı yayın kaydı bu sürümde yok.", color = MT.Ad)
                    if (duplicate) Text("Bu bağlantı daha önce indirildi.", color = MT.Ad, fontSize = 12.sp)
                    OutlinedTextField(name, { name = it }, label = { Text("Dosya adı") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(folder, { folder = it }, label = { Text("Klasör") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(com.movietrack.app.download.OutputStore.displayFolder(folder), fontSize = 11.sp) })
                    if (i.qualities.size > 1) {
                        Text("Kalite", color = MT.Muted, fontSize = 12.sp)
                        i.qualities.forEach { q ->
                            Row(Modifier.fillMaxWidth().clickable { quality = q.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(quality == q.id, { quality = q.id })
                                Text(q.label + if (q.estSize > 0) " · ~${Format.bytes(q.estSize)}" else "")
                                if (q.audio == false && i.audios.isEmpty()) { Spacer(Modifier.padding(4.dp)); Tag("SESSİZ", MT.Danger) }
                            }
                        }
                    }
                    if (i.audios.isNotEmpty()) {
                        Text("Ses izleri", color = MT.Muted, fontSize = 12.sp)
                        i.audios.forEach { a ->
                            Row(Modifier.fillMaxWidth().clickable { if (a.id in audios) audios.remove(a.id) else audios.add(a.id) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(a.id in audios, { if (it) audios.add(a.id) else audios.remove(a.id) })
                                LangTag(a.label); Spacer(Modifier.padding(4.dp)); Text(a.name, color = MT.Text)
                            }
                        }
                    }
                    if (i.subtitles.isNotEmpty()) {
                        Text("Altyazılar", color = MT.Muted, fontSize = 12.sp)
                        i.subtitles.forEach { t ->
                            Row(Modifier.fillMaxWidth().clickable { if (t.id in subs) subs.remove(t.id) else subs.add(t.id) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(t.id in subs, { if (it) subs.add(t.id) else subs.remove(t.id) })
                                LangTag(t.label); Spacer(Modifier.padding(4.dp)); Text(t.name, color = MT.Text)
                            }
                        }
                    }
                    if (noAudio(i, quality, audios)) Text(
                        if (i.audios.isEmpty()) "⚠ Bu kalitede ses izi yok, video sessiz inecek. Sayfadaki ana listeyi (çoklu kalite) seçmeyi deneyin."
                        else "⚠ Hiç ses izi seçilmedi, video sessiz inecek. Yukarıdan bir ses izi seçin.",
                        color = MT.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (i.kind != "direct") {
                        Text("Çıktı", color = MT.Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(mode == "mp4", { mode = "mp4" }, label = { Text("Birleşik MP4") })
                            FilterChip(mode == "separate", { mode = "separate" }, label = { Text("Ayrı dosyalar") })
                        }
                        Text(if (mode == "mp4") "Video ve seçilen ses izleri tek MP4'te (dil etiketli). Altyazılar yanına .srt olarak." else "Video, her ses izi ve altyazı ayrı dosya.", color = MT.Muted, fontSize = 12.sp)
                        if (i.adBreaks > 0) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(skipAds, { skipAds = it }); Text("Reklam aralarını atla") }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = info != null && info?.drm == false && info?.live == false && name.isNotBlank(), onClick = {
                val i = info ?: return@TextButton
                scope.launch {
                    val req = com.movietrack.app.download.Requests.build(i, url, name, folder, quality, audios.toList(), subs.toList(), mode, skipAds,
                        referer, userAgent, pageTitle, inline)
                    app.downloads.add(req)
                    onQueued()
                }
            }) { Text(if (info?.let { noAudio(it, quality, audios) } == true) "Yine de indir" else "İndir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** Seçime göre çıkacak dosyada ses olmayacak mı (yalnızca kesin bilindiğinde true). */
private fun noAudio(i: Analyzer.Info, quality: String?, audios: List<String>): Boolean {
    if (i.kind == "direct") return false
    if (audios.any { a -> i.audios.any { it.id == a } }) return false
    return i.qualities.firstOrNull { it.id == quality }?.audio == false
}
