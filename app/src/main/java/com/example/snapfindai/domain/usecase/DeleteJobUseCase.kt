package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.repository.JobHistoryRepository
import javax.inject.Inject

/**
 * Deletes a past job and the copies of its matched photos this app was
 * keeping.
 *
 * Until this existed the only way to shed a job was to open it, select every
 * match and remove them one screen at a time, leaving an empty row for the
 * tidy-up to collect. Meanwhile each job holds a full-resolution copy of every
 * photo it matched, for as long as the app is installed.
 *
 * Deliberately leaves the device gallery alone. Anything the user downloaded
 * is theirs and lives outside this app entirely, so what a delete costs them
 * is the record of which photos matched -- recoverable by re-running the job
 * against the same ZIP -- and never a photo they chose to keep.
 */
class DeleteJobUseCase @Inject constructor(
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(jobId: Long) = jobHistoryRepository.deleteJob(jobId)
}
