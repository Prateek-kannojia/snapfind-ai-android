package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.facesdk.NoFaceDetectedException
import com.example.snapfindai.utils.FileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

// Use case owns all the business logic for this feature:
//   - unzip the event photos, hand real files (not bytes) to the repository
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
     */
    suspend operator fun invoke(
        selfieFile: File,
        zipFile: File,
        threshold: Float = FaceMatcher.DEFAULT_THRESHOLD,
        onProgress: ((scored: Int, total: Int) -> Unit)? = null,
    ): Result<List<FaceMatchResult>> = withContext(Dispatchers.IO) {
        val extractDir = File(zipFile.parentFile, "event_photos_${System.currentTimeMillis()}")
        try {
            // Before the expensive part, not after: unzipping a real event
            // folder takes minutes, and a selfie with no detectable face in
            // it should cost the user one second to find out, not all of that.
            // The result is carried to matchPhotos below rather than thrown
            // away, so the selfie is read and embedded exactly once per job.
            val preparedSelfie = faceMatchRepository.prepareSelfie(selfieFile)

            val eventPhotos = FileHelper.unzip(zipFile, extractDir)
                .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            if (eventPhotos.isEmpty()) {
                return@withContext Result.failure(Exception("No photos found in that ZIP file."))
            }

            val matches = faceMatchRepository.matchPhotos(preparedSelfie, eventPhotos, threshold, onProgress)

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
            // stable storage, and saves this as a new job -- the returned
            // list points at the new locations, which is what lets the
            // finally below delete the working directory wholesale.
            val persisted = jobHistoryRepository.saveJob(threshold, matches)
            Result.success(persisted)
        } catch (e: NoFaceDetectedException) {
            Result.failure(Exception("We couldn't find a face in your selfie. Try a clearer, well-lit photo.", e))
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
            // Every path, including failures: these live in filesDir now, so
            // nothing else will ever clean them up. A failed job used to
            // leave the entire extracted event folder behind.
            selfieFile.delete()
            zipFile.delete()
            extractDir.deleteRecursively()
        }
    }
}
