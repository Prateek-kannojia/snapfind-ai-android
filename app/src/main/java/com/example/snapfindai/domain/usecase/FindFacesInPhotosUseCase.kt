package com.example.snapfindai.domain.usecase

import com.example.snapfindai.data.remote.MatchItem
import com.example.snapfindai.domain.repository.JobRepository
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

// Use case owns all the business logic for this feature:
//   - what order to call the API steps
//   - how long to poll and when to give up
//   - what counts as a failure
//   - cleaning up temp files when done
//
// The repository just fetches data. The ViewModel just drives UI state.
// This class is the only place that knows HOW the feature works end-to-end.
class FindFacesInPhotosUseCase @Inject constructor(
    private val repository: JobRepository
) {
    suspend operator fun invoke(
        selfieFile: File,
        zipFile: File,
        threshold: Double = 0.5
    ): Result<List<MatchItem>> {
        return try {
            // Step 1: upload files, get back a job ID
            val jobId = repository.uploadJob(selfieFile, zipFile).job_id

            // Step 2: tell the backend to start face matching
            // The backend returns immediately — actual processing runs in its background
            repository.triggerProcessing(jobId, threshold)

            // Step 3: poll until the backend finishes (business rule: max 2 minutes)
            waitForCompletion(jobId)

            // Step 4: fetch the matched photos
            Result.success(repository.getJobMatches(jobId))

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Always clean up temp files regardless of success or failure
            selfieFile.delete()
            zipFile.delete()
        }
    }

    private suspend fun waitForCompletion(jobId: String) {
        // Poll every 2 seconds, give up after 60 attempts (2 minutes total)
        repeat(60) {
            when (repository.getJobStatus(jobId).status) {
                "completed" -> return
                "failed" -> throw Exception(
                    "Face matching failed on the server. Try uploading a clearer selfie."
                )
            }
            delay(2_000)
        }
        throw Exception("Processing timed out after 2 minutes.")
    }
}