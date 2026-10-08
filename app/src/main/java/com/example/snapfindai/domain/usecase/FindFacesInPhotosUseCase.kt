package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.EventPhoto
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.PhotoMatch
import com.example.snapfindai.domain.model.UserFacingException
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.facesdk.NoFaceDetectedException
import com.example.snapfindai.utils.FileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject

// Use case owns all the business logic for this feature:
//   - unzip the event photos, hand real files (not bytes) to the repository
//   - know how far a previous attempt got, and carry on from there
//   - decide what counts as a failure, and give it a clear message
//   - clean up temp files when done -- but NOT the matched photos
//     themselves, since ResultsScreen still needs to display them
//   - persist the result so it survives an app restart
//
// The repository just decides matches. The ViewModel just drives UI state.
// This class is the only place that knows HOW the feature works end-to-end.
class FindFacesInPhotosUseCase @Inject constructor(
    private val faceMatchRepository: FaceMatchRepository,
    private val jobHistoryRepository: JobHistoryRepository,
) {
    /**
     * All of this is blocking file work -- unzipping an event folder, deleting
     * what didn't match, relocating what did -- so it owns its dispatcher
     * rather than trusting the caller's. It used to run on whatever thread
     * called it, which was the ViewModel's main-thread scope.
     *
     * [jobId] is created by the caller, once per background work request, and
     * handed in rather than created here. A retried attempt therefore works
     * on the same job instead of starting a new one -- which is what stopped
     * every process kill from orphaning a row nothing would ever resume.
     *
     * **Resumes rather than restarts.** A killed attempt is re-entered here
     * from the top, so this reads the job's checkpoint first and skips what
     * is already done: the extraction if it finished, and every photo already
     * scored. That isn't only an optimisation. A job whose attempts each
     * start from zero on a device that kills before any of them finish never
     * terminates at all -- WorkManager promises the work eventually
     * completes, and without this that promise is unsatisfiable. Progress
     * makes it converge.
     *
     * The invariant the whole thing rests on: this function is safe to call
     * repeatedly for the same [jobId], and every call either advances the job
     * or finishes it, never redoing or losing work.
     */
    suspend operator fun invoke(
        jobId: Long,
        selfieFile: File,
        zipFile: File,
        threshold: Float = FaceMatcher.DEFAULT_THRESHOLD,
        onProgress: ((scored: Int, total: Int) -> Unit)? = null,
    ): Result<List<FaceMatchResult>> = withContext(Dispatchers.IO) {
        // A previous attempt may have finished everything and been killed
        // before it could report success -- in which case redoing the work
        // would wipe and re-save photos that are already there. Nothing to do
        // but hand back what's already recorded.
        if (!jobHistoryRepository.needsWork(jobId)) {
            return@withContext Result.success(jobHistoryRepository.getJob(jobId)?.matches.orEmpty())
        }

        // Resolved in the finally below on every path the app controls. What's
        // left unresolved is a job that started and never ended -- which only
        // happens when the process is killed mid-run, and is the only way the
        // app can find that out.
        var completed = false

        // Named by the job it belongs to, so leftover working directories can
        // later be matched against the jobs that own them. Only the photos
        // that actually match are ever written here -- a few dozen, where the
        // whole archive used to be unpacked.
        val matchesDir = File(zipFile.parentFile, "matches_$jobId")
        try {
            // Where a previous attempt of this same job got to, if there was
            // one. Read once, up front: everything after this is this
            // attempt's own progress, which it already knows.
            val checkpoint = jobHistoryRepository.checkpointFor(jobId)

            // Before the expensive part, not after: scoring a real event
            // folder takes minutes, and a selfie with no detectable face in
            // it should cost the user one second to find out, not all of that.
            // The result is carried to matchPhotos below rather than thrown
            // away, so the selfie is read and embedded exactly once per
            // attempt.
            val preparedSelfie = faceMatchRepository.prepareSelfie(selfieFile)

            // ZipFile, not ZipInputStream: it reads the archive's central
            // directory, so the entry list is known at once and any entry can
            // be read directly. A sequential stream would have to be re-read
            // from the start to reach photo 214 on every resumed attempt.
            ZipFile(zipFile).use { archive ->
                // Sorted by entry name, which is what makes the scored cursor
                // mean anything: it is a count into this order, so the order
                // has to be identical on every attempt at this job.
                val eventPhotos = eventPhotosIn(archive)
                if (eventPhotos.isEmpty()) {
                    return@withContext Result.failure(UserFacingException("No photos found in that ZIP file."))
                }

                // Worked out for the whole list up front, so a given photo
                // gets the same filename on every attempt. Disambiguating
                // only the photos this attempt happens to score would hand
                // the same name to two different photos after a resume.
                val fileNames = workFileNames(eventPhotos)
                matchesDir.mkdirs()

                // Coerced because the two can legitimately disagree: a job
                // whose row outlived its working directory reads as further
                // along than there are photos to score.
                val alreadyScored = checkpoint.scoredCount.coerceAtMost(eventPhotos.size)

                // Reported before any work, so a resumed job's progress picks
                // up where it stopped. Without it the bar sits at 0% until the
                // first photo of this attempt is scored, which on a
                // nearly-finished job looks like the work was thrown away.
                onProgress?.invoke(alreadyScored, eventPhotos.size)

                val found = mutableListOf<FaceMatchResult>()
                faceMatchRepository.matchPhotos(
                    selfie = preparedSelfie,
                    eventPhotos = eventPhotos.drop(alreadyScored),
                    threshold = threshold,
                ) { scoredThisAttempt, match ->
                    // Written to disk before the checkpoint names it, so a row
                    // can never point at a file that was never finished.
                    val kept = match?.let { keep(it, matchesDir, fileNames) }
                    val scored = alreadyScored + scoredThisAttempt
                    // The checkpoint, written per photo. A few milliseconds
                    // against a second or more of scoring, and it is what
                    // turns "the process died" from "start again" into "carry
                    // on from here".
                    jobHistoryRepository.recordScored(jobId, scored, kept)
                    if (kept != null) found += kept
                    onProgress?.invoke(scored, eventPhotos.size)
                }

                val matches = checkpoint.matches + found

                // Nothing to persist -- a job with zero matches shouldn't leave
                // a row behind for the history grid to show as an empty card.
                if (matches.isEmpty()) {
                    return@withContext Result.success(emptyList())
                }

                // Relocates matched photos out of the working directory into
                // stable storage and marks the job complete -- the returned list
                // points at the new locations, which is what lets the finally
                // below delete the working directory wholesale.
                val persisted = jobHistoryRepository.completeJob(jobId, matches)
                completed = true
                Result.success(persisted)
            }
        } catch (e: NoFaceDetectedException) {
            Result.failure(UserFacingException("We couldn't find a face in your selfie. Try a clearer, well-lit photo.", e))
        } catch (e: CancellationException) {
            // Rethrown, never turned into a Result.failure: CancellationException
            // extends Exception, so the generic catch below would otherwise
            // swallow it and report a cancelled job to the user as a failed
            // one -- and leave the coroutine looking like it completed
            // normally, which is how structured concurrency gets broken.
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // NonCancellable because this has to run after a cancel, and a
            // suspend DB call in an already-cancelled coroutine throws
            // immediately -- which would leave the row looking interrupted
            // when the user deliberately stopped it, and have the next launch
            // try to resume something nobody wants.
            //
            // Reached on success-with-no-matches, every failure, and
            // cancellation. Not reached on process death, which is exactly
            // the case that should leave the row behind.
            withContext(NonCancellable) {
                if (!completed) jobHistoryRepository.abandonJob(jobId)
            }

            // Every path, including failures: these live in filesDir now, so
            // nothing else will ever clean them up. A failed job used to
            // leave the entire extracted event folder behind.
            selfieFile.delete()
            zipFile.delete()
            matchesDir.deleteRecursively()
        }
    }

    /**
     * The job's photos, in an order that is the same on every attempt.
     *
     * The sort is the load-bearing part. A scored cursor is a count into this
     * list, so if the order varied between attempts the cursor would point at
     * a different photo each time -- silently skipping some photos and
     * re-scoring others, which is worse than not resuming at all. Entry order
     * inside an archive is whatever the tool that built it chose, so it is
     * sorted here rather than trusted.
     */
    private fun eventPhotosIn(archive: ZipFile): List<EventPhoto> =
        archive.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in PHOTO_EXTENSIONS }
            .map { ZipEntryPhoto(archive, it) }
            .sortedBy { it.name }
            .toList()

    /**
     * The filename each photo gets if it is kept, decided for the whole list
     * at once.
     *
     * Two properties this has to have together. **Unique**, because an
     * archive organised into folders ("day1/IMG_001.jpg", "day2/IMG_001.jpg")
     * flattens into one directory here, and without that the second photo
     * overwrites the first. And **the same on every attempt**, because a
     * resumed attempt scores only part of the list: disambiguating as it goes
     * would number the photos differently depending on where it started, and
     * hand one photo's name to another.
     *
     * Deciding it for every photo up front gives both, since the list itself
     * is identical on every attempt. The name is also the one the user
     * eventually sees in their gallery, which is why it keeps the photo's own
     * filename rather than being an index.
     */
    private fun workFileNames(photos: List<EventPhoto>): Map<String, String> {
        val taken = mutableSetOf<String>()
        return photos.associate { photo -> photo.name to uniqueName(baseNameOf(photo.name), taken) }
    }

    /**
     * Just the filename, never a path. An entry name comes from the archive,
     * which came from wherever the user got it, so "../../evil.jpg" is a
     * thing it can say -- dropping every path segment is what stops that
     * naming a file outside this job's directory, rather than a check that
     * has to be remembered.
     */
    private fun baseNameOf(entryName: String): String {
        val base = entryName.substringAfterLast('/').substringAfterLast('\\')
        return if (base.isBlank() || base == "." || base == "..") "photo.jpg" else base
    }

    /**
     * The original filename when it's free, and only disambiguated when it
     * genuinely clashes -- "IMG_001.jpg" then "IMG_001-2.jpg".
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

    /**
     * Writes a matched photo out of the archive, which is the only thing a
     * job writes at all now.
     *
     * The bytes are re-read rather than carried through the matcher: it is
     * one entry out of a local file, and it keeps the photo's *original*
     * bytes, not the capped-and-rotated bitmap that was scored. What ends up
     * in the user's gallery is the photo they took.
     */
    private fun keep(match: PhotoMatch, matchesDir: File, fileNames: Map<String, String>): FaceMatchResult {
        val dest = File(matchesDir, fileNames.getValue(match.photo.name))
        try {
            FileHelper.writeAtomically(dest, match.photo.readBytes())
        } catch (e: IOException) {
            throw UserFacingException("There wasn't enough room to save the photos that matched.", e)
        }
        return FaceMatchResult(photo = dest, distance = match.distance)
    }

    /** One entry of an open archive, read only if and when it is scored. */
    private class ZipEntryPhoto(private val archive: ZipFile, private val entry: ZipEntry) : EventPhoto {
        override val name: String get() = entry.name
        override fun readBytes(): ByteArray = archive.getInputStream(entry).use { it.readBytes() }
    }

    private companion object {
        val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
