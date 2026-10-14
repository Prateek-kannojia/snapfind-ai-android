package com.quantumweavers.snapfindai.domain.usecase

import com.quantumweavers.snapfindai.domain.model.JobSummary
import com.quantumweavers.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Every saved job, newest first, as a stream -- lightweight summaries for the
 * history grid on the main screen.
 *
 * Replaces a one-shot `GetJobHistoryUseCase`, which made keeping the grid
 * truthful the caller's job: it had to re-read after finishing a job, after
 * deleting jobs, after a batch save moved the saved ticks, and on coming back
 * from Results. Five places that each had to remember, and a sixth was always
 * going to be added without one. Observing instead means a write updates the
 * grid because it was a write, not because somebody wired up a callback.
 */
class ObserveJobHistoryUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    operator fun invoke(): Flow<List<JobSummary>> = jobHistoryRepository.observeJobSummaries()
}
