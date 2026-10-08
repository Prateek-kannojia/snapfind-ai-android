package com.example.snapfindai.domain.model

/**
 * How far a job got before it stopped, so a new attempt can carry on instead
 * of starting the whole thing again.
 *
 * WorkManager guarantees that work *eventually completes*; it does not make an
 * attempt resumable -- a killed worker's next attempt re-enters `doWork()` at
 * line one. For a job measured in minutes on a device that kills aggressively,
 * that is not merely slow: every attempt can be killed before it finishes, and
 * the job never terminates. This is what makes it converge.
 *
 * A fresh job reads as [NONE], so the resuming path and the first-run path are
 * the same code with different numbers in it.
 */
data class JobCheckpoint(
    /** Photos already scored, counted into the job's deterministically ordered photo list. */
    val scoredCount: Int,
    /**
     * Matches found by previous attempts, still in the job's working
     * directory. Filtered to files that are actually present, so a checkpoint
     * can never hand back a match whose file has gone.
     */
    val matches: List<FaceMatchResult>,
) {
    companion object {
        val NONE = JobCheckpoint(scoredCount = 0, matches = emptyList())
    }
}
