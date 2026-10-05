package com.example.snapfindai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.snapfindai.utils.DownloadNotificationHelper
import com.example.snapfindai.utils.MatchingNotifications
import com.example.snapfindai.work.AbandonedJobSweeper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SnapFindApplication : Application(), Configuration.Provider {

    /**
     * WorkManager builds workers reflectively, so by default it can only
     * construct ones with no dependencies. This factory is what lets
     * MatchPhotosWorker take the same injected use cases every other layer
     * uses, instead of reaching for a service locator.
     *
     * Paired with the manifest entry that removes WorkManager's automatic
     * initializer -- otherwise the default factory would already have won by
     * the time this is read.
     */
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @Inject lateinit var abandonedJobSweeper: AbandonedJobSweeper

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        sweepAbandonedJobs()
    }

    /**
     * Process start is the right moment for this, and `onCreate` is the wrong
     * thread for it: deleting a half-extracted event folder can mean
     * gigabytes, and this runs before any UI exists. So it's launched and left
     * to finish on its own -- nothing waits on the result, and if the process
     * is killed again before it completes, the next start simply repeats it.
     *
     * Deliberately not tied to a screen or a job: the scope outlives both,
     * because what it cleans up belongs to neither.
     */
    private fun sweepAbandonedJobs() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { abandonedJobSweeper.sweep() }
        }
    }

    // A channel must exist before any notification can be posted to it, on
    // API 26+ -- creating them here, once, at app startup is the standard
    // pattern (creating one again with the same id is a harmless no-op).
    //
    // Two channels rather than one, because they're interruptions of
    // different kinds: a job's progress is ongoing and low-importance, while
    // "your photos are saved" is a completed action worth a bit more
    // attention. Separate channels also let the user silence one without
    // losing the other.
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotificationHelper.CHANNEL_ID,
                "Photo downloads",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Progress and completion status for photos saved to your gallery"
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                MatchingNotifications.CHANNEL_ID,
                "Photo matching",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while your photos are being matched against your selfie"
            }
        )
    }
}
