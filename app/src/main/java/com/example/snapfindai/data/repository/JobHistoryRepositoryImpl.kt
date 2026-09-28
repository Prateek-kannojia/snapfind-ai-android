package com.example.snapfindai.data.repository

import android.content.Context
import com.example.snapfindai.data.local.JobDao
import com.example.snapfindai.data.local.JobEntity
import com.example.snapfindai.data.local.MatchedPhotoEntity
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.JobHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobHistoryRepositoryImpl @Inject constructor(
    private val jobDao: JobDao,
    @ApplicationContext private val context: Context,
) : JobHistoryRepository {

    // filesDir, not cacheDir: the OS can clear cache under storage pressure
    // at any time, which would leave the database pointing at files that no
    // longer exist. filesDir only goes away if the app itself is uninstalled.
    private val savedPhotosDir = File(context.filesDir, "saved_matches").apply { mkdirs() }

    override suspend fun saveJob(threshold: Float, matches: List<FaceMatchResult>): List<FaceMatchResult> {
        clearSavedPhotos()

        val relocated = matches.map { match ->
            val dest = File(savedPhotosDir, match.photo.name)
            match.photo.copyTo(dest, overwrite = true)
            match.photo.delete()
            match.copy(photo = dest)
        }

        jobDao.replaceWithNewJob(
            JobEntity(timestamp = System.currentTimeMillis(), threshold = threshold),
            relocated.map { MatchedPhotoEntity(jobId = 0, photoPath = it.photo.absolutePath, distance = it.distance) },
        )

        return relocated
    }

    override suspend fun getLastJob(): SavedJob? {
        val job = jobDao.getLastJob() ?: return null
        val photos = jobDao.getMatchedPhotosForJob(job.id)
        return SavedJob(
            timestamp = job.timestamp,
            matches = photos.map { FaceMatchResult(photo = File(it.photoPath), distance = it.distance, savedAt = it.savedAt) },
        )
    }

    override suspend fun markSavedToGallery(photo: File, savedAt: Long) {
        jobDao.markSaved(photo.absolutePath, savedAt)
    }

    override suspend fun removeMatches(photos: List<File>) {
        photos.forEach { it.delete() }
        jobDao.deleteMatchedPhotos(photos.map { it.absolutePath })
    }

    private fun clearSavedPhotos() {
        savedPhotosDir.listFiles()?.forEach { it.delete() }
    }
}
