package com.quantumweavers.snapfindai.domain.usecase

import com.quantumweavers.snapfindai.domain.model.SavedJob
import com.quantumweavers.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject

/** Reads back the last completed job, if any -- so the UI can resume showing it after an app restart. */
class GetLastJobUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(): SavedJob? = jobHistoryRepository.getLastJob()
}
