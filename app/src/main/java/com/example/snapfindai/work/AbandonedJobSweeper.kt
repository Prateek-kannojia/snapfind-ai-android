package com.example.snapfindai.work

import android.content.Context
import androidx.work.WorkManager
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.utils.FileHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Clears up after runs the process was killed in the middle of.
 *
 * Runs at process start rather than at the start of the next job. Cleaning up
 * on the way *into* a job put a potentially multi-gigabyte delete on the
 * critical path of the user's tap, and conflated "set up my run" with
 * "recover from an abandoned one". End-of-job cleanup remains the primary
 * mechanism -- the use case's own `finally` -- and this exists only for the
 * one case that can't reach: nothing runs when a process is killed, and a
 * work request cancelled while its worker wasn't running never executes
 * again to clean up after itself.
 *
 * The decision is not a timeout. "How old is this?" was only ever a proxy for
 * a question that can be answered directly: is the work request that owns
 * this still live? If it is, it's going to be resumed and must be left
 * strictly alone. If it isn't, nothing will ever pick it up.
 *
 * Driven by directories rather than by database rows, which matters: a run
 * whose work was cancelled before its worker ever started has a directory but
 * no row, and a row-driven sweep would never see it. Each directory is named
 * by the request that owns it, so the liveness question is answerable without
 * consulting anything else -- and a brand new run's request is live by
 * definition, so the sweep structurally cannot touch it. The previous version
 * took one liveness snapshot and then emptied the shared directory wholesale,
 * which could destroy the inputs of a job started while it was running.
 */
@Singleton
class AbandonedJobSweeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobHistoryRepository: JobHistoryRepository,
    private val workManager: WorkManager,
) {

    suspend fun sweep() {
        sweepWorkingDirectories()
        sweepJobsWithoutWork()
    }

    /** Deletes the scratch directory of every request that is no longer going to run. */
    private suspend fun sweepWorkingDirectories() {
        val directories = FileHelper.jobWorkDir(context).listFiles() ?: return
        for (directory in directories) {
            if (!directory.isDirectory) continue
            if (isWorkLive(directory.name)) continue
            directory.deleteRecursively()
        }
    }

    /**
     * Second pass for rows whose directory is already gone -- a run that got
     * far enough to clean its files but not to resolve its own status. Left
     * alone, these would sit as permanently "running" jobs that the history
     * tidy-up deliberately refuses to touch.
     */
    private suspend fun sweepJobsWithoutWork() {
        for (job in jobHistoryRepository.abandonedJobs()) {
            if (isWorkLive(job.workId)) continue
            jobHistoryRepository.abandonJob(job.jobId)
        }
    }

    private suspend fun isWorkLive(workId: String?): Boolean {
        // Not a request id at all, or never linked to one, so nothing owns it.
        val id = workId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
        val info = runCatching { workManager.getWorkInfoByIdFlow(id).first() }.getOrNull() ?: return false
        return !info.state.isFinished
    }
}
