package com.movietrack.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.movietrack.app.App
import com.movietrack.app.MainActivity
import com.movietrack.app.R
import com.movietrack.app.data.DownloadEntity
import com.movietrack.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Notifications {
    const val CH_PROGRESS = "progress"
    const val CH_DONE = "done"
    const val ID_SERVICE = 1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "İndirme ilerlemesi", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Tamamlanan indirmeler", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context, tab: String = "downloads"): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Uygulamaya özel ekler (ör. Duraklat/İptal düğmeleri). */
    @Volatile var decorate: (Context, NotificationCompat.Builder) -> Unit = { _, _ -> }

    fun progress(ctx: Context): Notification {
        val m = App.instance.downloads
        val liveMap = m.live.value
        val live = liveMap.values
        val bytes = live.sumOf { it.bytes }
        val total = live.sumOf { it.total }
        val speed = live.sumOf { it.speed }
        val eta = live.maxOfOrNull { it.etaSec } ?: 0L
        // Uygulamadaki çubukla aynı ölçü: parça/aşama ilerlemesi varsa o (birleştirme, altyazı), yoksa bayt
        fun frac(p: LiveProgress): Float? = when {
            p.segTotal > 0 -> p.segDone.toFloat() / p.segTotal
            p.total > 0 -> p.bytes.toFloat() / p.total
            else -> null
        }
        val fracs = live.mapNotNull { frac(it) }
        val pct = if (fracs.isNotEmpty()) (fracs.average() * 100).toInt().coerceIn(0, 100) else 0
        val names = m.runningNames
        val title = if (names.size == 1) names.values.first() else "${m.activeCount} indirme sürüyor"
        // Tek indirme indirme dışı bir aşamadaysa (birleştirme, altyazı) metin o aşamayı söyler
        val phase = live.singleOrNull()?.phase?.takeIf { it.isNotBlank() && it != "İndiriliyor" }
        val text = phase ?: listOf(
            Format.bytes(bytes) + if (total > 0) " / ${Format.bytes(total)}" else "",
            if (speed > 0) "${Format.bytes(speed)}/sn" else "",
            if (eta > 0) (if (eta < 60) "$eta sn kaldı" else if (eta < 3600) "${eta / 60} dk kaldı" else "${eta / 3600} sa kaldı") else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        val b = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Bağlanıyor…" })
            .setSubText(if (fracs.isNotEmpty()) "%$pct" else null)
            .setProgress(100, pct, fracs.isEmpty())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
        if (names.size > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title).setSummaryText(text)
            names.entries.take(5).forEach { (id, n) ->
                val p = liveMap[id]
                val pp = p?.let { frac(it) }?.let { "%${(it * 100).toInt()} · " } ?: ""
                style.addLine(pp + n)
            }
            b.setStyle(style)
        }
        decorate(ctx, b)
        return b.build()
    }

    fun finished(ctx: Context, e: DownloadEntity, error: String?) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (error == null) "İndirme tamamlandı" else "İndirme başarısız")
            .setContentText(if (error == null) e.fileName else "${e.fileName}: $error")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, if (error == null) "library" else "downloads"))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(1000 + e.id.toInt(), n) } catch (_: SecurityException) {}
    }
}

/** İndirmeler sürerken uygulama arka plandayken de çalışmayı sürdüren ön plan servisi. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.progress(this),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            while (true) {
                delay(1000)
                val m = App.instance.downloads
                if (m.activeCount == 0) {
                    delay(1500)
                    if (m.activeCount == 0) { stopSelf(); break }
                }
                try { NotificationManagerCompat.from(this@DownloadService).notify(Notifications.ID_SERVICE, Notifications.progress(this@DownloadService)) } catch (_: SecurityException) {}
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) } catch (_: Exception) {
                // Arka planda başlatma kısıtı: indirme yine sürer, uygulama açılınca servis başlar
            }
        }
    }
}
