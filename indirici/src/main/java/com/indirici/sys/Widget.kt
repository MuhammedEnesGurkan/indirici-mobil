package com.indirici.sys

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.indirici.ui.DarkColors
import com.indirici.ui.LightColors
import com.movietrack.app.App
import com.movietrack.app.MainActivity
import com.movietrack.app.R
import com.movietrack.app.data.Status
import com.movietrack.app.util.Format
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Ana ekran widget'ı: 2×2 özet, 4×2 liste. "Panodan indir" uygulamayı panodaki bağlantıyla açar. */
class IndiriciWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val m = App.instance.downloads
        provideContent {
            val all = m.all.collectAsStateCompat()
            val live = m.live.value
            GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) {
                Content(context, all.filter { it.status in Status.ACTIVE }.map { e ->
                    val p = live[e.id]
                    val total = p?.total ?: e.totalBytes
                    val bytes = p?.bytes ?: e.downloadedBytes
                    Row3(e.fileName, if (total > 0) bytes.toFloat() / total else 0f, p?.speed ?: 0L)
                })
            }
        }
    }

    data class Row3(val name: String, val frac: Float, val speed: Long)

    @Composable
    private fun Content(ctx: Context, items: List<Row3>) {
        val wide = LocalSize.current.width >= 240.dp
        val speed = items.sumOf { it.speed }
        val c = GlanceTheme.colors
        Column(GlanceModifier.fillMaxSize().background(c.surface).cornerRadius(28.dp).padding(16.dp).clickable(actionStartActivity(openIntent(ctx, false)))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_notification), null, GlanceModifier.size(20.dp).background(c.primary).cornerRadius(10.dp).padding(2.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text(if (wide && items.isNotEmpty()) "indirici · ${items.size} indirme sürüyor" else "indirici", style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
            Spacer(GlanceModifier.height(10.dp))
            if (!wide) {
                Text(if (items.isEmpty()) "Aktif yok" else "${items.size} aktif", style = TextStyle(color = c.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium))
                Text(if (speed > 0) "${Format.bytes(speed)}/sn" else "Bekliyor", style = TextStyle(color = c.onSurfaceVariant, fontSize = 12.sp))
            } else if (items.isEmpty()) {
                Text("Şu an inen bir şey yok", style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp))
            } else items.take(2).forEach { r ->
                Row(GlanceModifier.fillMaxWidth()) {
                    Text(r.name, maxLines = 1, style = TextStyle(color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium), modifier = GlanceModifier.defaultWeight())
                    Text("%${(r.frac * 100).toInt()}", style = TextStyle(color = c.primary, fontSize = 12.sp))
                }
                LinearProgressIndicator(r.frac, GlanceModifier.fillMaxWidth().height(4.dp).padding(bottom = 2.dp), color = c.primary, backgroundColor = c.surfaceVariant)
                Spacer(GlanceModifier.height(6.dp))
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(GlanceModifier.fillMaxWidth().height(40.dp).background(c.primary).cornerRadius(20.dp).clickable(actionStartActivity(openIntent(ctx, true))),
                verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Panodan indir", style = TextStyle(color = c.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)

        fun openIntent(ctx: Context, paste: Boolean) = Intent(ctx, MainActivity::class.java)
            .setAction(if (paste) ACTION_PASTE else Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("tab", "downloads")

        const val ACTION_PASTE = "com.indirici.PASTE"

        /** İndirmeler değiştikçe widget'ı en fazla 3 sn'de bir yeniler (yalnızca widget eklenmişse). */
        fun watch(app: App) {
            val m = app.downloads
            m.scope.launch {
                var last = 0L
                combine(m.all, m.live) { a, _ -> a.count { it.status in Status.ACTIVE } }.collect {
                    val now = System.currentTimeMillis()
                    if (now - last < 3000) return@collect
                    last = now
                    try {
                        if (GlanceAppWidgetManager(app).getGlanceIds(IndiriciWidget::class.java).isNotEmpty()) IndiriciWidget().updateAll(app)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<List<T>>.collectAsStateCompat(): List<T> =
    androidx.compose.runtime.produceState(initialValue = emptyList(), this) { value = first(); collect { value = it } }.value

class IndiriciWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = IndiriciWidget()
}
