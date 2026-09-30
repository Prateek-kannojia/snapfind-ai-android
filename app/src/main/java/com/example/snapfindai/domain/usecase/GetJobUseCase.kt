package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject

/** Reads back one specific past job by id -- reopening a job from history, as opposed to [GetLastJobUseCase]'s "whichever is newest". */
class GetJobUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(jobId: Long): SavedJob? = jobHistoryRepository.getJob(jobId)
}
