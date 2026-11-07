package com.indirici.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.movietrack.app.App
import com.movietrack.app.data.Status
import com.movietrack.app.download.Notifications
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Zamanlayıcı: indirmeler yalnızca seçilen saat aralığında ve günlerde (isteğe bağlı: şarj olurken) başlar.
 * Pencere kapanınca süren indirmeler sıraya geri alınır; açılınca kaldıkları yerden sürer.
 */
object Scheduler {
    private const val ACTION_TICK = "com.indirici.SCHEDULE_TICK"

    fun charging(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Şu an pencerenin içinde miyiz (gün + saat). Bitiş başlangıçtan küçükse gece yarısını aşar. */
    fun inWindow(now: Calendar = Calendar.getInstance()): Boolean {
        val ui = App.instance.ui
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val s = ui.schedStart
        val e = ui.schedEnd
        val dayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Pazartesi = 0
        return if (s <= e) {
            m in s until e && dayOn(dayIdx)
        } else {
            // Gece yarısını aşan pencere: başlangıç günü kuralı geçerli
            if (m >= s) dayOn(dayIdx) else if (m < e) dayOn((dayIdx + 6) % 7) else false
        }
    }

    private fun dayOn(i: Int) = (App.instance.ui.schedDays shr i) and 1 == 1

    fun allowed(ctx: Context): Boolean {
        val ui = App.instance.ui
        if (!ui.schedOn) return true
        if (!inWindow()) return false
        if (ui.schedCharging && !charging(ctx)) return false
        return true
    }

    /** Durumu uygula: pencere açıksa başlat, kapalıysa süren indirmeleri sıraya geri al. Sonra bir sonraki kontrolü kur. */
    fun tick(ctx: Context) {
        val app = App.instance
        val m = app.downloads
        if (allowed(ctx)) m.schedule()
        else m.scope.launch {
            val dao = app.db.downloads()
            for (id in m.runningNames.keys.toList()) {
                m.pause(id).join()
                dao.get(id)?.let { if (it.status == Status.PAUSED) { it.status = Status.QUEUED; dao.update(it) } }
            }
        }
        arm(ctx)
    }

    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, SystemReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val ui = App.instance.ui
        if (!ui.schedOn) return
        // Bir sonraki sınır (başlangıç ya da bitiş) ya da en geç 15 dk sonra (şarj durumu değişmiş olabilir)
        val now = Calendar.getInstance()
        val next = listOf(ui.schedStart, ui.schedEnd).map { mins ->
            (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, mins / 60); set(Calendar.MINUTE, mins % 60); set(Calendar.SECOND, 5); set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }.minOrNull() ?: return
        val at = minOf(next, System.currentTimeMillis() + 15 * 60_000L)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun install(app: App) {
        app.downloads.gate = { allowed(app) }
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (App.instance.ui.schedOn) tick(c) } }
        ContextCompat.registerReceiver(app, r, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        arm(app)
        // Bildirimdeki düğmeler
        Notifications.decorate = { ctx, b ->
            b.addAction(0, "Tümünü duraklat", action(ctx, SystemReceiver.ACTION_PAUSE_ALL, 0))
            val ids = App.instance.downloads.runningNames.keys
            if (ids.size == 1) b.addAction(0, "İptal", action(ctx, SystemReceiver.ACTION_CANCEL, ids.first()))
            b.setPriority(NotificationCompat.PRIORITY_LOW)
        }
    }

    private fun action(ctx: Context, a: String, id: Long) = PendingIntent.getBroadcast(ctx, (a.hashCode() + id).toInt(),
        Intent(ctx, SystemReceiver::class.java).setAction(a).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Zamanlayıcı alarmı, açılış ve bildirim düğmeleri. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val m = App.instance.downloads
        when (i.action) {
            ACTION_PAUSE_ALL -> m.pauseAll()
            ACTION_CANCEL -> i.getLongExtra("id", 0).takeIf { it > 0 }?.let { m.remove(it, true) }
            else -> Scheduler.tick(ctx) // alarm ya da cihaz açılışı
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.indirici.PAUSE_ALL"
        const val ACTION_CANCEL = "com.indirici.CANCEL"
    }
}

/** Cihaz açılınca zamanlayıcı alarmını yeniden kurar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.arm(ctx)
    }
}
