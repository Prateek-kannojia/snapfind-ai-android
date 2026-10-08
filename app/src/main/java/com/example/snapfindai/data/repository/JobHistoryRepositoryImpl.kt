package com.example.snapfindai.data.repository

import android.content.Context
import com.example.snapfindai.data.local.JobDao
import com.example.snapfindai.data.local.JobEntity
import com.example.snapfindai.data.local.JobStatus
import com.example.snapfindai.data.local.MatchedPhotoEntity
import com.example.snapfindai.data.local.PendingMatchEntity
import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobCheckpoint
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.JobHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
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

    override suspend fun findOrStartJob(workId: String, threshold: Float): Long = withContext(Dispatchers.IO) {
        jobDao.getJobIdForWork(workId)
            ?: jobDao.insertJob(
                JobEntity(
                    timestamp = System.currentTimeMillis(),
                    threshold = threshold,
                    status = JobStatus.Running.name,
                    workId = workId,
                )
            )
    }

    override suspend fun needsWork(jobId: Long): Boolean = withContext(Dispatchers.IO) {
        jobDao.getJobStatus(jobId) == JobStatus.Running.name
    }

    override suspend fun abandonJob(jobId: Long) = withContext(Dispatchers.IO) {
        jobDao.deleteJob(jobId)
    }

    override suspend fun deleteJob(jobId: Long) = withContext(Dispatchers.IO) {
        // Only ever this app's own storage. The gallery is MediaStore under
        // Pictures, which nothing here addresses -- deleting a job cannot
        // reach a photo the user chose to keep, by construction rather than
        // by being careful.
        File(savedPhotosDir, jobId.toString()).deleteRecursively()
        // Row last: the photo rows go with it via CASCADE, so if this fails
        // the job still lists and can be deleted again, rather than becoming
        // a row pointing at files that are gone.
        jobDao.deleteJob(jobId)
    }

    override suspend fun checkpointFor(jobId: Long): JobCheckpoint = withContext(Dispatchers.IO) {
        val job = jobDao.getJobById(jobId) ?: return@withContext JobCheckpoint.NONE
        JobCheckpoint(
            scoredCount = job.scoredCount,
            extractionComplete = job.extractionComplete,
            // Existence is checked here, once, rather than trusted by the
            // caller: these paths were written by an attempt that did not
            // finish, and a match whose file has gone would fail minutes later
            // inside completeJob's copy, with the job already looking done.
            matches = jobDao.getPendingMatches(jobId)
                .map { File(it.photoPath) to it.distance }
                .filter { (photo, _) -> photo.exists() }
                .map { (photo, distance) -> FaceMatchResult(photo = photo, distance = distance) },
        )
    }

    override suspend fun markExtractionComplete(jobId: Long) = withContext(Dispatchers.IO) {
        jobDao.markExtractionComplete(jobId)
    }

    override suspend fun recordScored(jobId: Long, scoredCount: Int, match: FaceMatchResult?) =
        withContext(Dispatchers.IO) {
            jobDao.checkpointScored(
                jobId = jobId,
                scoredCount = scoredCount,
                match = match?.let {
                    PendingMatchEntity(jobId = jobId, photoPath = it.photo.absolutePath, distance = it.distance)
                },
            )
        }

    override suspend fun abandonedJobs(): List<AbandonedJob> = withContext(Dispatchers.IO) {
        jobDao.getJobsWithStatus(JobStatus.Running.name)
            .map { AbandonedJob(jobId = it.id, workId = it.workId) }
    }

    // Dispatchers.IO: copying every matched photo into stable storage is real
    // file work, and a job can match hundreds of full-size photos.
    override suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>): List<FaceMatchResult> =
        withContext(Dispatchers.IO) {
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
                // The gallery name is derived while this copy runs rather
                // than computed later: every byte is already passing through
                // here, so hashing costs nothing extra. Doing it on demand
                // instead would mean re-reading every matched photo on every
                // screen resume, because that's where the saved-state
                // reconcile asks for these names.
                val digest = copyAndDigest(match.photo, dest)
                match.photo.delete()
                match.copy(photo = dest, galleryName = galleryNameFor(digest, dest.name))
            }

            // One transaction: the photos and the status become visible
            // together or not at all. As two separate writes there was a
            // window where a process death left photos recorded against a
            // still-running job, and the retry inserted a duplicate set.
            jobDao.completeJobWithPhotos(
                jobId = jobId,
                photos = relocated.map {
                    MatchedPhotoEntity(
                        jobId = jobId,
                        photoPath = it.photo.absolutePath,
                        distance = it.distance,
                        galleryName = requireNotNull(it.galleryName),
                    )
                },
                completeStatus = JobStatus.Complete.name,
            )

            relocated
        }

    override suspend fun getLastJob(): SavedJob? {
        val job = jobDao.getLastJob(JobStatus.Complete.name) ?: return null
        return toSavedJob(job)
    }

    override suspend fun getJob(jobId: Long): SavedJob? {
        val job = jobDao.getJobById(jobId) ?: return null
        return toSavedJob(job)
    }

    // A pure read. It used to call deleteEmptyJobs first, which made listing
    // the history mutate the database -- surprising on its own, and it meant
    // the cleanup only happened when something happened to be reading. It now
    // runs where the emptying actually occurs, in removeMatches below.
    override suspend fun getAllJobSummaries(): List<JobSummary> = withContext(Dispatchers.IO) {
        jobDao.getAllJobs(JobStatus.Complete.name).map { job ->
            val photos = jobDao.getMatchedPhotosForJob(job.id)
            val files = photos.map { File(it.photoPath) }
            JobSummary(
                id = job.id,
                timestamp = job.timestamp,
                matchCount = photos.size,
                previewPhoto = files.firstOrNull(),
                // One stat per file, on top of a query this already does, so
                // the size costs nothing extra worth avoiding.
                sizeBytes = files.sumOf { it.length() },
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
        // Removing every match of a job leaves a row with nothing to show, so
        // the tidy-up belongs here, where that can actually happen -- rather
        // than on whatever next read happened to trigger it. Scoped to
        // finished jobs: a running one legitimately has no photos yet.
        jobDao.deleteEmptyJobs(JobStatus.Complete.name)
    }

    /**
     * Copies [source] to [destination] and returns the SHA-256 of what was
     * copied, in one pass. Two results from one read of the file.
     */
    private fun copyAndDigest(source: File, destination: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return digest.digest()
    }

    /**
     * Content-addressed, so the same photo always maps to the same gallery
     * entry and a different photo never maps to one already there. The
     * filename is kept on the end so the result is still recognisable in the
     * user's gallery rather than being an opaque hash.
     *
     * 16 hex characters is 64 bits of the digest: far more than enough to
     * separate the few thousand photos an install will ever see, and short
     * enough to leave the filename readable.
     */
    private fun galleryNameFor(digest: ByteArray, fileName: String): String {
        val fingerprint = digest.take(8).joinToString("") { "%02x".format(it) }
        return "SnapFindAI_${fingerprint}_$fileName"
    }

    /**
     * The original filename when it's free, and only disambiguated when it
     * genuinely clashes -- "IMG_001.jpg" then "IMG_001-2.jpg". Worth the
     * extra care rather than prefixing every file with an index, because this
     * name is what ends up in the user's gallery when they download the photo
     * (see galleryNameFor below).
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
            matches = photos.map {
                FaceMatchResult(
                    photo = File(it.photoPath),
                    distance = it.distance,
                    savedAt = it.savedAt,
                    galleryName = it.galleryName,
                )
            },
        )
    }
}
