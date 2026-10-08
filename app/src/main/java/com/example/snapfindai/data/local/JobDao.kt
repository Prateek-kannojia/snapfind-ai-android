package com.example.snapfindai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface JobDao {
    @Insert
    suspend fun insertJob(job: JobEntity): Long

    @Insert
    suspend fun insertMatchedPhotos(photos: List<MatchedPhotoEntity>)

    /** Marks a job finished. Until this runs, its row stays [JobStatus.Running] -- see [JobStatus] for why that's the interruption signal. */
    @Query("UPDATE jobs SET status = :status WHERE id = :jobId")
    suspend fun setJobStatus(jobId: Long, status: String)

    /** For a job that ended without results to show: failed, or cancelled. Cascades its matched photos. */
    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: Long)

    /** Jobs that started and never reached an ending -- i.e. the process died mid-job. Oldest first. */
    @Query("SELECT * FROM jobs WHERE status = :status ORDER BY timestamp ASC")
    suspend fun getJobsWithStatus(status: String): List<JobEntity>

    /** Null when the row is gone. Lets a restarted worker tell "still to do" from "already finished" before redoing minutes of work. */
    @Query("SELECT status FROM jobs WHERE id = :jobId")
    suspend fun getJobStatus(jobId: Long): String?

    /** The job a work request is already carrying, if it has started one. Null on a request's first run. */
    @Query("SELECT id FROM jobs WHERE workId = :workId LIMIT 1")
    suspend fun getJobIdForWork(workId: String): Long?

    /**
     * Records that a job has scored [scoredCount] of its photos, and the match
     * that photo produced if it produced one.
     *
     * One transaction, and in this order, because the two writes mean
     * different things if they come apart. Match first, cursor second: a
     * process killed between them loses nothing -- the next attempt re-scores
     * that one photo and the unique index absorbs the duplicate. Cursor first
     * would mean a photo marked done whose match was never written, and a
     * match silently missing from the results is the one outcome the user
     * cannot detect.
     */
    @Transaction
    suspend fun checkpointScored(jobId: Long, scoredCount: Int, match: PendingMatchEntity?) {
        if (match != null) insertPendingMatch(match)
        setScoredCount(jobId, scoredCount)
    }

    /** IGNORE rather than REPLACE: a re-scored photo produces an identical row, and replacing it would churn its id for nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingMatch(match: PendingMatchEntity)

    @Query("UPDATE jobs SET scoredCount = :scoredCount WHERE id = :jobId")
    suspend fun setScoredCount(jobId: Long, scoredCount: Int)

    /** `ORDER BY id` so a resumed job's matches keep the order they were found in, which is the order the grid will show. */
    @Query("SELECT * FROM pending_matches WHERE jobId = :jobId ORDER BY id")
    suspend fun getPendingMatches(jobId: Long): List<PendingMatchEntity>

    @Query("DELETE FROM pending_matches WHERE jobId = :jobId")
    suspend fun deletePendingMatches(jobId: Long)

    /**
     * Finishes a job in one transaction: its photos and its status become
     * visible together or not at all.
     *
     * Previously two separate calls, which left a window where the process
     * could die with photos written but the job still marked running. The
     * retry would then redo everything and insert a *second* set of rows for
     * the same paths -- duplicate keys in the results grid, which throws, and
     * keeps throwing until app data is cleared.
     */
    @Transaction
    suspend fun completeJobWithPhotos(jobId: Long, photos: List<MatchedPhotoEntity>, completeStatus: String) {
        insertMatchedPhotos(photos)
        // In the same transaction as the real results, so there is no instant
        // where a job is both finished and still carrying notes about how to
        // resume it.
        deletePendingMatches(jobId)
        setJobStatus(jobId, completeStatus)
    }

    // The three queries below filter to finished jobs on purpose. A job's row
    // now exists while it is still running, with no matched photos attached
    // yet -- so without the filter an in-progress job would surface as a
    // history card showing zero photos, and would be picked up as "the last
    // completed job" by Results.
    @Query("SELECT * FROM jobs WHERE status = :status ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastJob(status: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun getJobById(jobId: Long): JobEntity?

    @Query("SELECT * FROM jobs WHERE status = :status ORDER BY timestamp DESC")
    suspend fun getAllJobs(status: String): List<JobEntity>

    /**
     * Self-heals finished jobs left with zero matched photos -- a stray row
     * from before empty match lists were handled, or from a process death
     * between the job row and its photos being written.
     *
     * Scoped to the given status for a reason that would otherwise be a bug:
     * a *running* job legitimately has no photos yet, and this would delete
     * it out from under itself.
     */
    @Query("DELETE FROM jobs WHERE status = :status AND id NOT IN (SELECT DISTINCT jobId FROM matched_photos)")
    suspend fun deleteEmptyJobs(status: String)

    /**
     * `ORDER BY id` is load-bearing, not cosmetic: without it SQLite makes no
     * ordering promise at all, so two separate calls could return the same
     * rows in different orders -- and Results and the photo viewer each call
     * this independently for the same job. Insertion order is the order the
     * job found its matches in -- which is the order its photos were scored,
     * sorted by path -- and that is what the user sees in the grid.
     */
    @Query("SELECT * FROM matched_photos WHERE jobId = :jobId ORDER BY id")
    suspend fun getMatchedPhotosForJob(jobId: Long): List<MatchedPhotoEntity>

    @Query("UPDATE matched_photos SET savedAt = :savedAt WHERE photoPath = :photoPath")
    suspend fun markSaved(photoPath: String, savedAt: Long)

    /** The undo of [markSaved] -- for photos the user has since deleted from their gallery outside the app. */
    @Query("UPDATE matched_photos SET savedAt = NULL WHERE photoPath IN (:photoPaths)")
    suspend fun clearSaved(photoPaths: List<String>)

    @Query("DELETE FROM matched_photos WHERE photoPath IN (:photoPaths)")
    suspend fun deleteMatchedPhotos(photoPaths: List<String>)
}
