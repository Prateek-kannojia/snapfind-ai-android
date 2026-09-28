package com.example.snapfindai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface JobDao {
    @Insert
    suspend fun insertJob(job: JobEntity): Long

    @Insert
    suspend fun insertMatchedPhotos(photos: List<MatchedPhotoEntity>)

    @Query("SELECT * FROM jobs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastJob(): JobEntity?

    @Query("SELECT * FROM matched_photos WHERE jobId = :jobId")
    suspend fun getMatchedPhotosForJob(jobId: Long): List<MatchedPhotoEntity>

    @Query("DELETE FROM jobs")
    suspend fun deleteAllJobs()

    @Query("UPDATE matched_photos SET savedAt = :savedAt WHERE photoPath = :photoPath")
    suspend fun markSaved(photoPath: String, savedAt: Long)

    @Query("DELETE FROM matched_photos WHERE photoPath IN (:photoPaths)")
    suspend fun deleteMatchedPhotos(photoPaths: List<String>)

    /**
     * Only one job is ever kept today (see JobHistoryRepositoryImpl), so
     * saving a new one replaces whatever was there -- deleting the old job
     * cascades to its matched_photos rows automatically.
     */
    @Transaction
    suspend fun replaceWithNewJob(job: JobEntity, photos: List<MatchedPhotoEntity>) {
        deleteAllJobs()
        val jobId = insertJob(job)
        insertMatchedPhotos(photos.map { it.copy(jobId = jobId) })
    }
}
