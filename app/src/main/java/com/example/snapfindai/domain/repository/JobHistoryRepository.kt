package com.example.snapfindai.domain.repository

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import java.io.File

/** Keeps every completed job so past results survive an app restart and can be reopened, not just the most recent one. */
interface JobHistoryRepository {
    /**
     * Persists [matches] into stable storage as a new job -- previously
     * saved jobs are kept, not replaced. Returns an updated match list
     * pointing at the newly persisted file locations -- the caller's
     * original [matches] list's files may no longer exist after this call
     * (they've been moved, not copied-and-kept), so the caller must use the
     * returned list, not the one it passed in.
     */
    suspend fun saveJob(threshold: Float, matches: List<FaceMatchResult>): List<FaceMatchResult>

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
