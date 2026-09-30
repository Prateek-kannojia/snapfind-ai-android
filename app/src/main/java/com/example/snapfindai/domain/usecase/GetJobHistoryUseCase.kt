package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject

/** Every saved job, newest first, as lightweight summaries for the history grid on the main screen. */
class GetJobHistoryUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(): List<JobSummary> = jobHistoryRepository.getAllJobSummaries()
}
