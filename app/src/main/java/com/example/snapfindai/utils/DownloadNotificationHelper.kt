package com.example.snapfindai.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Shows a system notification when a batch of photos finishes saving to the
 * gallery. Separate from the in-app snackbar (ResultsScreen shows both) --
 * this is the one that still reaches the user if they've backgrounded the
 * app mid-download.
 */
object DownloadNotificationHelper {
    const val CHANNEL_ID = "photo_downloads"
    private const val NOTIFICATION_ID = 1001

    /**
     * [lastSavedUri] becomes the tap target (opens that photo in the
     * device's default gallery/photos app) when available -- null on
     * Android 9 and below, where MediaStore doesn't hand back a Uri; the
     * notification still shows, it just opens the gallery app generally
     * instead of a specific photo.
     */
    fun showCompleted(context: Context, savedCount: Int, failedCount: Int, lastSavedUri: Uri?) {
        val title = if (failedCount == 0) "Photos saved" else "$savedCount saved, $failedCount failed"
        val text = if (failedCount == 0) {
            "$savedCount photo(s) saved to your gallery"
        } else {
            "$savedCount photo(s) saved, $failedCount couldn't be saved"
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            if (lastSavedUri != null) setDataAndType(lastSavedUri, "image/*") else type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, viewIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+) -- the snackbar
            // already covers the in-app case, so this is a silent no-op
            // rather than a crash.
        }
    }
}
