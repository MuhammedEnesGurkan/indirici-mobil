package com.movietrack.app

import android.app.Application
import com.indirici.ui.UiPrefs
import com.movietrack.app.data.AppDatabase
import com.movietrack.app.data.Settings
import com.movietrack.app.download.DownloadManager
import com.movietrack.app.download.Notifications

/**
 * indirici uygulama sınıfı. Ortak arka uç (indirme yöneticisi, veritabanı, tarayıcı) `App.instance`
 * üzerinden bu sınıfa ulaşır; sınıf adı ve paketi bu yüzden Movie Track ile aynıdır.
 */
class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var downloads: DownloadManager
        private set
    lateinit var ui: UiPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "indirici ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "İş parçacığı: ${t.name}\n\n" + android.util.Log.getStackTraceString(e),
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        db = AppDatabase.create(this)
        settings = Settings(this)
        ui = UiPrefs(this)
        Notifications.createChannels(this)
        // Telefonun çözemediği sesler (AC-3/E-AC-3/DTS/MP2) birleştirmede AAC'ye çevrilir
        com.movietrack.app.download.Muxer.converterFactory = { androidx.media3.decoder.ffmpeg.FfmpegToAac.create(it) }
        downloads = DownloadManager(this)
        com.indirici.sys.Scheduler.install(this)
        downloads.restore()
        com.indirici.sys.IndiriciWidget.watch(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
