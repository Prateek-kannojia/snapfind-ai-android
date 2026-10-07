package com.example.snapfindai.domain.repository

import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import java.io.File

/** Keeps every completed job so past results survive an app restart and can be reopened, not just the most recent one. */
interface JobHistoryRepository {
    /**
     * The job [workId] is carrying, creating it on that request's first run.
     *
     * Keyed on the work request so that retries converge rather than fork: an
     * attempt killed mid-run is rescheduled with the same request id, finds
     * the same job, and continues it. Creating a row per *attempt* instead
     * meant every process kill left an orphan nothing would ever resume.
     *
     * Called from inside the worker, deliberately. Creating the row before
     * enqueuing meant a three-step sequence -- record the job, build the
     * request, link them, enqueue -- with a gap at each step where a crash
     * left a job that no work was coming for. Doing it here means the row
     * exists only once something is genuinely running, and an enqueue that
     * never happens leaves nothing behind at all.
     *
     * A job still in this state when nothing is running it was interrupted by
     * the process dying — see [abandonedJobs].
     */
    suspend fun findOrStartJob(workId: String, threshold: Float): Long

    /**
     * Whether [jobId] still has work outstanding -- false if it's already
     * finished, or if the row is gone. A retried attempt checks this before
     * starting, since the previous attempt may have completed everything and
     * died before it could report success.
     */
    suspend fun needsWork(jobId: Long): Boolean

    /**
     * Finishes the job [jobId] started, persisting [matches] into stable
     * storage. Returns an updated match list pointing at the newly persisted
     * file locations -- the caller's original [matches] list's files may no
     * longer exist after this call (they've been moved, not copied-and-kept),
     * so the caller must use the returned list, not the one it passed in.
     */
    suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>): List<FaceMatchResult>

    /**
     * Forgets a job that ended with nothing to show -- it failed, or the user
     * cancelled it. Distinct from simply never calling [completeJob], which
     * leaves the job looking interrupted rather than abandoned on purpose.
     */
    suspend fun abandonJob(jobId: Long)

    /**
     * Deletes a finished job: its row, its matched photo records, and the
     * copies of those photos this app was storing.
     *
     * **Does not touch the device gallery.** Anything the user downloaded
     * lives in MediaStore under Pictures, which this reaches by no path at
     * all -- so a deleted job costs them the record of what matched, never a
     * photo they chose to keep. That separation is the guarantee, not a
     * coincidence of the current implementation, and the confirmation the
     * user sees says so.
     *
     * Distinct from [abandonJob], which drops a row for a job that never
     * produced anything to store.
     */
    suspend fun deleteJob(jobId: Long)

    /**
     * Jobs that were started and never finished, oldest first. Since every
     * in-app ending either completes or abandons a job, anything still in
     * this state was interrupted by the process dying — which is the only
     * way the app finds out that happened.
     *
     * Each carries the work request that was running it, because the row
     * alone can't say whether anything is still coming to resume it.
     */
    suspend fun abandonedJobs(): List<AbandonedJob>

    /** The most recently completed job, if any -- what a fresh "Find My Photos" run should show right after finishing. */
    suspend fun getLastJob(): SavedJob?

    /** One specific past job by id, for reopening it from job history. */
    suspend fun getJob(jobId: Long): SavedJob?

    /** Every saved job, newest first, as lightweight summaries for a history list/grid -- not the full match lists. */
    suspend fun getAllJobSummaries(): List<JobSummary>

    /** Records that [photo] (matched against by its persisted path) has been saved to the device gallery. */
    suspend fun markSavedToGallery(photo: File, savedAt: Long)

    /**
     * Forgets that [photos] were ever saved -- used when they've since been
     * deleted from the gallery outside the app, so the saved tick keeps
     * meaning "this is in your gallery right now" rather than "you tapped
     * download at some point".
     */
    suspend fun clearSavedToGallery(photos: List<File>)

    /**
     * Permanently removes [photos] from whichever job they belong to --
     * from persistence and their stable-storage copies. Does NOT touch
     * anything already saved to the device gallery; this only curates the
     * in-app candidate list (e.g. "I don't want to download this one, take
     * it out before I hit Download All").
     */
    suspend fun removeMatches(photos: List<File>)
}
