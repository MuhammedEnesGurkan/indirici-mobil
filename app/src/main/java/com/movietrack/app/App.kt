package com.movietrack.app

import android.app.Application
import com.movietrack.app.data.AppDatabase
import com.movietrack.app.data.Settings
import com.movietrack.app.download.DownloadManager
import com.movietrack.app.download.Notifications

class App : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var downloads: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Çökme olursa ayrıntıyı kaydet; bir sonraki açılışta kullanıcıya gösterilir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "Movie Track ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "İş parçacığı: ${t.name}\n\n" + android.util.Log.getStackTraceString(e),
                )
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        db = AppDatabase.create(this)
        settings = Settings(this)
        Notifications.createChannels(this)
        downloads = DownloadManager(this)
        downloads.restore()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
