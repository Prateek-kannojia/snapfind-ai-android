package com.example.snapfindai.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.usecase.FindFacesInPhotosUseCase
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.utils.FileHelper
import com.example.snapfindai.utils.MatchingNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Runs a matching job outside any screen's lifetime.
 *
 * Previously this ran in `UploadViewModel`'s coroutine scope, which tied a
 * five-minute job to the Activity: leaving the app killed it, and on a
 * memory-constrained device being killed was the normal outcome rather than
 * the exception. A worker survives the screen, runs as a foreground service
 * so the OS stops treating it as disposable, and -- because WorkManager
 * persists its own queue -- is rescheduled by itself if the process dies
 * anyway or the phone reboots.
 *
 * Takes file *paths*, not the content Uris the user picked. A Uri from
 * `GetContent()` carries a read grant scoped to the Activity that received
 * it; by the time a worker runs, that grant may be gone. So the caller copies
 * both files into the app's own storage first, while the grant is definitely
 * valid, and hands over paths that stay readable.
 */
@HiltWorker
class MatchPhotosWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val findFacesInPhotos: FindFacesInPhotosUseCase,
    private val jobHistoryRepository: JobHistoryRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Both sides derive the directory from the request id, so no path is
        // ever passed around and a worker cannot be handed one belonging to
        // another run. Its contents were written by whoever enqueued this,
        // before the Uri grants expired.
        val workDir = FileHelper.requestWorkDir(applicationContext, id.toString())
        val selfieFile = File(workDir, SELFIE_FILE_NAME)
        val zipFile = File(workDir, ZIP_FILE_NAME)
        if (!selfieFile.exists() || !zipFile.exists()) {
            return Result.failure(workDataOf(KEY_ERROR to "Couldn't read the selected files."))
        }

        // Keyed on this request, so a retry after a process kill finds the
        // job the previous attempt started instead of forking a new one.
        val jobId = jobHistoryRepository.findOrStartJob(id.toString(), FaceMatcher.DEFAULT_THRESHOLD)

        setForeground(foregroundInfo(scored = null, total = null))

        // The use case reports progress through a plain callback, while
        // setProgress and setForeground both suspend -- so the callback
        // publishes into this and a separate coroutine does the suspending
        // work of reporting it. distinctUntilChanged on the percentage keeps
        // that down to at most 100 notification updates for a job of any
        // size, instead of one per photo.
        val progress = MutableStateFlow<Pair<Int, Int>?>(null)

        return coroutineScope {
            val reporter = launch {
                progress
                    .distinctUntilChanged { old, new -> percentOf(old) == percentOf(new) }
                    .collect { reported ->
                        if (reported == null) return@collect
                        val (scored, total) = reported
                        setProgress(workDataOf(KEY_SCORED to scored, KEY_TOTAL to total))
                        setForeground(foregroundInfo(scored, total))
                    }
            }

            val outcome = findFacesInPhotos(
                jobId = jobId,
                selfieFile = selfieFile,
                zipFile = zipFile,
                onProgress = { scored, total -> progress.value = scored to total },
            )
            reporter.cancel()

            outcome.fold(
                onSuccess = { matches ->
                    MatchingNotifications.showCompleted(applicationContext, matches.size)
                    Result.success(workDataOf(KEY_MATCH_COUNT to matches.size))
                },
                onFailure = { error ->
                    // Deliberately not retried. Every failure the use case
                    // surfaces is about this input -- an unreadable selfie, no
                    // face in it, a ZIP with no photos -- so running it again
                    // unchanged would fail the same way, slowly.
                    Result.failure(
                        workDataOf(KEY_ERROR to (error.message ?: "An unknown error occurred."))
                    )
                },
            )
        }
    }

    private fun percentOf(progress: Pair<Int, Int>?): Int? {
        val (scored, total) = progress ?: return null
        return if (total <= 0) null else scored * 100 / total
    }

    private fun foregroundInfo(scored: Int?, total: Int?): ForegroundInfo {
        val notification = MatchingNotifications.progress(applicationContext, scored, total).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The type has to match the one declared on WorkManager's service
            // in the manifest, or Android 14+ rejects the service at runtime.
            ForegroundInfo(
                MatchingNotifications.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(MatchingNotifications.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Unique work name: starting a second job replaces the first rather than two running at once. */
        const val WORK_NAME = "match_photos"

        /** Fixed names inside the request's own directory -- unambiguous because no other run shares that directory. */
        const val SELFIE_FILE_NAME = "selfie.jpg"
        const val ZIP_FILE_NAME = "events.zip"

        const val KEY_SCORED = "scored"
        const val KEY_TOTAL = "total"
        const val KEY_MATCH_COUNT = "match_count"
        const val KEY_ERROR = "error"
    }
}
