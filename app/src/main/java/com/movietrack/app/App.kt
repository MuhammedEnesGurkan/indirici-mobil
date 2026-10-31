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
