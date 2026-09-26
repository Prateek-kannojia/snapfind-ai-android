package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.facesdk.NoFaceDetectedException
import com.example.snapfindai.utils.FileHelper
import java.io.File
import javax.inject.Inject

// Use case owns all the business logic for this feature:
//   - unzip the event photos, hand real files (not bytes) to the repository
//   - decide what counts as a failure, and give it a clear message
//   - clean up temp files when done -- but NOT the matched photos
//     themselves, since ResultsScreen still needs to display them
//
// The repository just decides matches. The ViewModel just drives UI state.
// This class is the only place that knows HOW the feature works end-to-end.
class FindFacesInPhotosUseCase @Inject constructor(
    private val faceMatchRepository: FaceMatchRepository,
) {
    suspend operator fun invoke(
        selfieFile: File,
        zipFile: File,
        threshold: Float = FaceMatcher.DEFAULT_THRESHOLD,
    ): Result<List<FaceMatchResult>> {
        val extractDir = File(zipFile.parentFile, "event_photos_${System.currentTimeMillis()}")
        return try {
            val eventPhotos = FileHelper.unzip(zipFile, extractDir)
                .filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            if (eventPhotos.isEmpty()) {
                return Result.failure(Exception("No photos found in that ZIP file."))
            }

            val matches = faceMatchRepository.matchPhotos(selfieFile, eventPhotos, threshold)

            // Free the disk space of everything that didn't match -- only
            // the results ResultsScreen will actually display are kept.
            val matchedFiles = matches.map { it.photo }.toSet()
            eventPhotos.filterNot { it in matchedFiles }.forEach { it.delete() }

            Result.success(matches)
        } catch (e: NoFaceDetectedException) {
            Result.failure(Exception("We couldn't find a face in your selfie. Try a clearer, well-lit photo.", e))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            selfieFile.delete()
            zipFile.delete()
        }
    }
}
