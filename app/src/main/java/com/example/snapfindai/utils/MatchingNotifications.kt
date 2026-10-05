package com.example.snapfindai.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.snapfindai.MainActivity

/**
 * The notification a matching job runs under.
 *
 * Not optional decoration: a foreground service is required to show one, and
 * that's the deal that stops the OS killing a five-minute job the moment the
 * user switches apps. It turns out to be the better UX anyway -- the user can
 * leave, watch progress in the shade, and be told when it's done, instead of
 * being pinned to a screen watching a ring.
 */
object MatchingNotifications {
    const val CHANNEL_ID = "photo_matching"

    /** Distinct from the download notification's id, so a finished job and a finished download don't overwrite each other. */
    const val PROGRESS_NOTIFICATION_ID = 2001
    private const val COMPLETE_NOTIFICATION_ID = 2002

    /**
     * [scored]/[total] null means the job is still unzipping or embedding the
     * selfie, where there's no photo count to report yet -- shown as an
     * indeterminate bar rather than a fabricated 0%.
     */
    fun progress(context: Context, scored: Int?, total: Int?): NotificationCompat.Builder {
        val indeterminate = scored == null || total == null || total <= 0
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Finding your photos")
            .setContentText(
                if (indeterminate) "Preparing…" else "$scored of $total photos scanned"
            )
            .setProgress(total ?: 0, scored ?: 0, indeterminate)
            .setContentIntent(openAppIntent(context))
            // Ongoing + no timestamp: this is a running operation, not an
            // event, and it shouldn't be swipeable while the service needs it.
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
    }

    /**
     * Posted when a job finishes while the user isn't in the app -- which is
     * now a normal outcome rather than an edge case, since the job keeps
     * running after they leave. This is how the result gets delivered.
     */
    fun showCompleted(context: Context, matchCount: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                if (matchCount == 0) "No photos matched" else "Found $matchCount photo${if (matchCount == 1) "" else "s"}"
            )
            .setContentText(
                if (matchCount == 0) "Nothing in that batch matched your selfie" else "Tap to see your matches"
            )
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(COMPLETE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+). The results are
            // persisted either way and show up in Recent Jobs, so this is a
            // silent no-op rather than a crash.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
