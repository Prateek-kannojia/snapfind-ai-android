package com.example.snapfindai.domain.repository

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.SavedJob
import java.io.File

/**
 * Keeps the most recently completed job's results so they survive an app
 * restart. Deliberately schema-ready for full multi-job history later
 * (each saved job already has its own row, linked matched-photo rows) --
 * for now, saving a new job replaces whatever was saved before, since
 * there's no history UI yet for old jobs to be shown in.
 */
interface JobHistoryRepository {
    /**
     * Persists [matches] into stable storage and replaces whatever job was
     * saved before. Returns an updated match list pointing at the newly
     * persisted file locations -- the caller's original [matches] list's
     * files may no longer exist after this call (they've been moved, not
     * copied-and-kept), so the caller must use the returned list, not the
     * one it passed in.
     */
    suspend fun saveJob(threshold: Float, matches: List<FaceMatchResult>): List<FaceMatchResult>

    suspend fun getLastJob(): SavedJob?

    /** Records that [photo] (matched against by its persisted path) has been saved to the device gallery. */
    suspend fun markSavedToGallery(photo: File, savedAt: Long)

    /**
     * Permanently removes [photos] from the current job -- from persistence
     * and their stable-storage copies. Does NOT touch anything already
     * saved to the device gallery; this only curates the in-app candidate
     * list (e.g. "I don't want to download this one, take it out before I
     * hit Download All").
     */
    suspend fun removeMatches(photos: List<File>)
}
