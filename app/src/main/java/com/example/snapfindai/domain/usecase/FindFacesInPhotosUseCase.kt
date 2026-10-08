package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.FaceMatchResult
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
        // later be matched against the jobs that own them.
        val extractDir = File(zipFile.parentFile, "event_photos_$jobId")
        try {
            // Where a previous attempt of this same job got to, if there was
            // one. Read once, up front: everything after this is this
            // attempt's own progress, which it already knows.
            val checkpoint = jobHistoryRepository.checkpointFor(jobId)

            // Before the expensive part, not after: unzipping a real event
            // folder takes minutes, and a selfie with no detectable face in
            // it should cost the user one second to find out, not all of that.
            // The result is carried to matchPhotos below rather than thrown
            // away, so the selfie is read and embedded exactly once per job.
            // (Once per *attempt*, strictly -- an embedding is a second of
            // work and not worth persisting to save on a resume.)
            val preparedSelfie = faceMatchRepository.prepareSelfie(selfieFile)

            if (!checkpoint.extractionComplete) {
                // Checked before extracting rather than discovered during it: a
                // folder too big for the device otherwise fails minutes in, with
                // a raw I/O error and the storage already full.
                if (!FileHelper.hasRoomToExtract(zipFile)) {
                    // Reports what is actually required rather than the archive's
                    // own size, so the figure matches what the check demanded.
                    val neededMb = FileHelper.requiredSpaceToExtract(zipFile) / (1024 * 1024)
                    return@withContext Result.failure(
                        UserFacingException("Not enough free space. This needs about ${neededMb}MB free to unpack.")
                    )
                }

                // Skips entries a previous attempt already wrote, so this
                // costs only what is actually left to extract.
                FileHelper.unzip(
                    zipFile = zipFile,
                    destDir = extractDir,
                    maxTotalBytes = zipFile.length() * FileHelper.MAX_EXPANSION_FACTOR,
                )
                // Only now is the directory known to hold the whole archive.
                // Recorded before any scoring, because scoring a subset would
                // report "no photos of you here" from half an event.
                jobHistoryRepository.markExtractionComplete(jobId)
            }

            // Listed from the directory on both paths rather than taken from
            // unzip's return value, so a first attempt and a resumed one build
            // the identical list -- which is the whole basis of the scored
            // cursor meaning anything.
            val eventPhotos = eventPhotosIn(extractDir)
            if (eventPhotos.isEmpty()) {
                return@withContext Result.failure(UserFacingException("No photos found in that ZIP file."))
            }

            // Coerced because the two can legitimately disagree: once scoring
            // finishes, the non-matching photos are deleted, so a kill at that
            // moment leaves a cursor past the end of a now-shorter list.
            // Dropping that many still yields nothing left to score, which is
            // the right answer.
            val alreadyScored = checkpoint.scoredCount.coerceAtMost(eventPhotos.size)

            // Reported before any work, so a resumed job's progress picks up
            // where it stopped. Without it the bar sits at 0% until the first
            // photo of this attempt is scored, which on a nearly-finished job
            // looks like the work was thrown away.
            onProgress?.invoke(alreadyScored, eventPhotos.size)

            val matches = checkpoint.matches + faceMatchRepository.matchPhotos(
                selfie = preparedSelfie,
                eventPhotos = eventPhotos.drop(alreadyScored),
                threshold = threshold,
            ) { scoredThisAttempt, match ->
                val scored = alreadyScored + scoredThisAttempt
                // The checkpoint, written per photo. It costs a few
                // milliseconds against a second or more of scoring, and it is
                // what turns "the process died" from "start again" into "carry
                // on from here".
                jobHistoryRepository.recordScored(jobId, scored, match)
                onProgress?.invoke(scored, eventPhotos.size)
            }

            // Free the disk space of everything that didn't match before
            // saveJob starts copying -- the whole extracted folder goes in
            // the finally below either way, but on a nearly-full device the
            // copy needs that room now, not afterwards.
            val matchedFiles = matches.map { it.photo }.toSet()
            eventPhotos.filterNot { it in matchedFiles }.forEach { it.delete() }

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
            extractDir.deleteRecursively()
        }
    }

    /**
     * The job's photos, in an order that is the same on every attempt.
     *
     * The sort is the load-bearing part. A scored cursor is a count into this
     * list, so if the order varied between attempts the cursor would point at
     * a different photo each time -- silently skipping some photos and
     * re-scoring others, which is worse than not resuming at all.
     * `listFiles()` promises no order whatsoever, and ZIP entry order isn't
     * available once the archive has been unpacked and thrown away.
     *
     * Walks the tree rather than the top level because an event archive is
     * usually organised into folders ("day1/", "ceremony/"). Half-written
     * `.part` files from an interrupted extraction are excluded by the same
     * extension filter that excludes anything else that isn't a photo.
     */
    private fun eventPhotosIn(extractDir: File): List<File> =
        extractDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in PHOTO_EXTENSIONS }
            .sortedBy { it.relativeTo(extractDir).invariantSeparatorsPath }
            .toList()

    private companion object {
        val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
