package com.example.snapfindai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface JobDao {
    @Insert
    suspend fun insertJob(job: JobEntity): Long

    @Insert
    suspend fun insertMatchedPhotos(photos: List<MatchedPhotoEntity>)

    @Query("SELECT * FROM jobs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastJob(): JobEntity?

    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun getJobById(jobId: Long): JobEntity?

    @Query("SELECT * FROM jobs ORDER BY timestamp DESC")
    suspend fun getAllJobs(): List<JobEntity>

    /**
     * Self-heals jobs left with zero matched photos -- shouldn't happen
     * going forward (FindFacesInPhotosUseCase skips saveJob() entirely for
     * an empty match list now), but cleans up any stray row from before
     * that fix, or from a process death between insertJob() and
     * insertMatchedPhotos() succeeding.
     */
    @Query("DELETE FROM jobs WHERE id NOT IN (SELECT DISTINCT jobId FROM matched_photos)")
    suspend fun deleteEmptyJobs()

    /**
     * `ORDER BY id` is load-bearing, not cosmetic: without it SQLite makes no
     * ordering promise at all, so two separate calls could return the same
     * rows in different orders -- and Results and the photo viewer each call
     * this independently for the same job. Insertion order is also the order
     * the photos came out of the ZIP, which is what the user sees in the grid.
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
