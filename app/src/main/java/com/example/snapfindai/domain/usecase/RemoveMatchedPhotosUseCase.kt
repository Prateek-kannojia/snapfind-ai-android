package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject

/** Removes matches from the current job's candidate list -- curation, not deleting anything already saved to the gallery. */
class RemoveMatchedPhotosUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(matches: List<FaceMatchResult>) {
        jobHistoryRepository.removeMatches(matches.map { it.photo })
    }
}
