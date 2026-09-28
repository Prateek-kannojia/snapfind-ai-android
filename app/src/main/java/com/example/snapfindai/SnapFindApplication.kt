package com.example.snapfindai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.snapfindai.utils.DownloadNotificationHelper
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SnapFindApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // A channel must exist before any notification can be posted to it, on
    // API 26+ -- creating it here, once, at app startup is the standard
    // pattern (creating it again with the same id is a harmless no-op).
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DownloadNotificationHelper.CHANNEL_ID,
            "Photo downloads",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Progress and completion status for photos saved to your gallery"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
