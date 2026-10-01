package com.example.snapfindai.data.repository

import android.content.Context
import com.example.snapfindai.data.local.JobDao
import com.example.snapfindai.data.local.JobEntity
import com.example.snapfindai.data.local.MatchedPhotoEntity
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.JobHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // Dispatchers.IO: copying every matched photo into stable storage is real
    // file work, and a job can match hundreds of full-size photos.
    override suspend fun saveJob(threshold: Float, matches: List<FaceMatchResult>): List<FaceMatchResult> =
        withContext(Dispatchers.IO) {
            val jobId = jobDao.insertJob(JobEntity(timestamp = System.currentTimeMillis(), threshold = threshold))

            // Each job gets its own subdirectory, which keeps two different
            // jobs' photos apart. Cleared first because deleting a job frees
            // its id for SQLite to hand out again, and nothing deletes the
            // directory -- so a new job can otherwise land on top of a
            // deleted one's leftover files.
            val jobDir = File(savedPhotosDir, jobId.toString())
            jobDir.deleteRecursively()
            jobDir.mkdirs()

            // Within one job the names still have to be made unique: a single
            // event ZIP organised into folders ("day1/IMG_001.jpg",
            // "day2/IMG_001.jpg") flattens into one directory here, and
            // without this the second photo overwrites the first and both DB
            // rows end up pointing at the same file.
            val takenNames = mutableSetOf<String>()
            val relocated = matches.map { match ->
                val dest = File(jobDir, uniqueName(match.photo.name, takenNames))
                match.photo.copyTo(dest, overwrite = true)
                match.photo.delete()
                match.copy(photo = dest)
            }

            jobDao.insertMatchedPhotos(
                relocated.map { MatchedPhotoEntity(jobId = jobId, photoPath = it.photo.absolutePath, distance = it.distance) }
            )

            relocated
        }

    override suspend fun getLastJob(): SavedJob? {
        val job = jobDao.getLastJob() ?: return null
        return toSavedJob(job)
    }

    override suspend fun getJob(jobId: Long): SavedJob? {
        val job = jobDao.getJobById(jobId) ?: return null
        return toSavedJob(job)
    }

    override suspend fun getAllJobSummaries(): List<JobSummary> {
        // Also covers a job that's become empty because the user removed
        // every one of its matches from Results (see removeMatches below),
        // not just a stray row from before matches.isEmpty() was handled.
        jobDao.deleteEmptyJobs()
        return jobDao.getAllJobs().map { job ->
            val photos = jobDao.getMatchedPhotosForJob(job.id)
            JobSummary(
                id = job.id,
                timestamp = job.timestamp,
                matchCount = photos.size,
                previewPhoto = photos.firstOrNull()?.let { File(it.photoPath) },
            )
        }
    }

    override suspend fun markSavedToGallery(photo: File, savedAt: Long) {
        jobDao.markSaved(photo.absolutePath, savedAt)
    }

    override suspend fun clearSavedToGallery(photos: List<File>) {
        if (photos.isEmpty()) return
        jobDao.clearSaved(photos.map { it.absolutePath })
    }

    override suspend fun removeMatches(photos: List<File>) = withContext(Dispatchers.IO) {
        photos.forEach { it.delete() }
        jobDao.deleteMatchedPhotos(photos.map { it.absolutePath })
    }

    /**
     * The original filename when it's free, and only disambiguated when it
     * genuinely clashes -- "IMG_001.jpg" then "IMG_001-2.jpg". Worth the
     * extra care rather than prefixing every file with an index, because this
     * name is what ends up in the user's gallery when they download the photo
     * (see MediaStoreGalleryRepositoryImpl.displayNameFor).
     *
     * [taken] accumulates across one job's photos, so it's the caller's job
     * to use a fresh set per job.
     */
    private fun uniqueName(name: String, taken: MutableSet<String>): String {
        if (taken.add(name)) return name

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var attempt = 2
        while (true) {
            val candidate = "$base-$attempt$suffix"
            if (taken.add(candidate)) return candidate
            attempt++
        }
    }

    private suspend fun toSavedJob(job: JobEntity): SavedJob {
        val photos = jobDao.getMatchedPhotosForJob(job.id)
        return SavedJob(
            id = job.id,
            timestamp = job.timestamp,
            matches = photos.map { FaceMatchResult(photo = File(it.photoPath), distance = it.distance, savedAt = it.savedAt) },
        )
    }
}
