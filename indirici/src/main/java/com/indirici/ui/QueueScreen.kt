package com.indirici.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.indirici.sys.Scheduler
import com.movietrack.app.App
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.data.Status
import com.movietrack.app.util.Format
import com.movietrack.app.util.KV

private val DAYS = listOf("Pt" to "Pazartesi", "Sa" to "Salı", "Ça" to "Çarşamba", "Pe" to "Perşembe", "Cu" to "Cuma", "Ct" to "Cumartesi", "Pz" to "Pazar")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(onBack: () -> Unit) {
    val app = App.instance
    val ui = app.ui
    val s = app.settings
    val ctx = LocalContext.current
    val v1 by ui.version.collectAsState()
    val v2 by s.version.collectAsState()
    (v1 + v2).hashCode()
    val all by app.downloads.all.collectAsState(initial = emptyList())
    val c = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)

    val waiting = all.filter { it.status == Status.QUEUED || it.status == Status.WAITING_WIFI || it.status == Status.PAUSED }
        .sortedBy { KV.decode(it.meta)["prio"]?.toLongOrNull() ?: Long.MAX_VALUE }
    val order = remember { mutableStateListOf<DownloadEntity>() }
    LaunchedEffect(waiting.map { it.id }) { order.clear(); order.addAll(waiting) }
    var picker by remember { mutableStateOf<String?>(null) }

    fun changed() { Scheduler.tick(ctx) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Sıra ve zamanlayıcı") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Geri") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainerLow).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Aynı anda en fazla", style = MaterialTheme.typography.labelLarge)
                    Text("Diğerleri sırada bekler", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                Row(Modifier.clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerHigh), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { s.maxConcurrent = s.maxConcurrent - 1; app.downloads.schedule() }) { Icon(Icons.Rounded.Remove, "Azalt") }
                    Text("${s.maxConcurrent}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Aynı anda ${s.maxConcurrent} indirme" })
                    IconButton(onClick = { s.maxConcurrent = s.maxConcurrent + 1; app.downloads.schedule() }) { Icon(Icons.Rounded.Add, "Artır") }
                }
            }

            Header("Sıradakiler · ${order.size}")
            if (order.isEmpty()) Text("Sırada bekleyen indirme yok. Yeni indirmede \"Sıraya ekle\"yi seçersen burada sırasını belirleyebilirsin.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
            else ReorderList(order) { app.downloads.setPriorities(order.map { it.id }) }

            Header("Zamanlayıcı")
            Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ToggleRow(Icons.Rounded.Schedule, "Belirli saatlerde indir", "Gece tarifesi için ideal", ui.schedOn) { ui.schedOn = it; changed() }
                if (ui.schedOn) Column(Modifier.fillMaxWidth().background(c.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeBox("Başlangıç", ui.schedStart, Modifier.weight(1f)) { picker = "start" }
                        Icon(Icons.Rounded.ArrowForward, null, tint = c.onSurfaceVariant)
                        TimeBox("Bitiş", ui.schedEnd, Modifier.weight(1f)) { picker = "end" }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DAYS.forEachIndexed { i, (short, full) ->
                            val on = (ui.schedDays shr i) and 1 == 1
                            Box(Modifier.size(42.dp).clip(CircleShape).background(if (on) c.primary else c.surfaceContainerHigh)
                                .toggleable(on, role = Role.Checkbox) { ui.schedDays = ui.schedDays xor (1 shl i); changed() }
                                .semantics { contentDescription = full; stateDescription = if (on) "seçili" else "seçili değil" },
                                contentAlignment = Alignment.Center) {
                                Text(short, style = MaterialTheme.typography.labelLarge, color = if (on) c.onPrimary else c.onSurfaceVariant)
                            }
                        }
                    }
                    Text(if (Scheduler.inWindow()) "Şu an pencere açık; sıradakiler başlayabilir." else "Şu an pencere dışında; sıradakiler başlangıç saatini bekler.",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                ToggleRow(Icons.Rounded.Wifi, "Sadece Wi-Fi'dayken", "Mobil veride sırada bekler", s.wifiOnly) { s.wifiOnly = it; app.downloads.schedule() }
                ToggleRow(Icons.Rounded.BatteryChargingFull, "Sadece şarj olurken", "Zamanlayıcı açıkken geçerli", ui.schedCharging, enabled = ui.schedOn) { ui.schedCharging = it; changed() }
            }
        }
    }

    picker?.let { which ->
        val cur = if (which == "start") ui.schedStart else ui.schedEnd
        val st = rememberTimePickerState(cur / 60, cur % 60, is24Hour = true)
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (which == "start") "Başlangıç saati" else "Bitiş saati") },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { val m = st.hour * 60 + st.minute; if (which == "start") ui.schedStart = m else ui.schedEnd = m; picker = null; changed() }) { Text("Tamam") } },
            dismissButton = { TextButton(onClick = { picker = null }) { Text("Vazgeç") } })
    }
}

@Composable
private fun Header(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 32.dp, top = 12.dp, bottom = 4.dp))

@Composable
private fun TimeBox(label: String, mins: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    TextButton(onClick = onClick, modifier = modifier.clip(RoundedCornerShape(16.dp)).background(c.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            Text("%02d:%02d".format(mins / 60, mins % 60), style = MaterialTheme.typography.titleLarge, color = c.onSurface)
        }
    }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(c.surfaceContainerLow).toggleable(value, enabled, Role.Switch, onChange).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Switch(value, null, enabled = enabled)
    }
}

/** Tutamaktan uzun basıp sürükleyerek sıralanan liste. */
@Composable
private fun ReorderList(items: MutableList<DownloadEntity>, onDrop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val rowPx = with(LocalDensity.current) { 68.dp.toPx() }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
        items.forEachIndexed { index, e ->
            val active = dragging == e.id
            Row(
                Modifier.fillMaxWidth().height(68.dp).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) offset else 0f; scaleX = if (active) 1.02f else 1f; scaleY = if (active) 1.02f else 1f }
                    .then(if (active) Modifier.shadow(6.dp, RoundedCornerShape(16.dp)) else Modifier)
                    .background(if (active) c.surfaceContainerHighest else c.surfaceContainerLow)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.width(28.dp).padding(start = 8.dp))
                FileThumb(categoryOf(e), size = 40.dp, radius = 12.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(middleEllipsis(e.fileName, 34), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLabel(statusOf(e, null))
                        if (e.totalBytes > 0) Text(" · ${Format.bytes(e.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
                Box(Modifier.size(48.dp).semantics { contentDescription = "${index + 1}. sıradaki ${e.fileName}; taşımak için uzun basıp sürükle" }
                    .pointerInput(e.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = e.id; offset = 0f },
                            onDragEnd = { dragging = null; offset = 0f; onDrop() },
                            onDragCancel = { dragging = null; offset = 0f },
                            onDrag = { ch, amount ->
                                ch.consume()
                                offset += amount.y
                                val i = items.indexOfFirst { it.id == e.id }
                                if (offset > rowPx / 2 && i < items.lastIndex) { items.add(i + 1, items.removeAt(i)); offset -= rowPx }
                                else if (offset < -rowPx / 2 && i > 0) { items.add(i - 1, items.removeAt(i)); offset += rowPx }
                            },
                        )
                    }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DragIndicator, null, tint = c.onSurfaceVariant)
                }
            }
            if (index < items.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}
