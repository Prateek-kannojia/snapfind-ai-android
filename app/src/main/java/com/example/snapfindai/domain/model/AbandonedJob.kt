package com.example.snapfindai.domain.model

/**
 * A job that was started and never reached an ending -- the process died
 * mid-run. [workId] is the background work request that was carrying it, and
 * is what decides its fate: if that request is still live, something is going
 * to resume this job and it must be left alone. If the request is finished or
 * gone, nothing will ever pick this up again and it's garbage.
 *
 * Null [workId] means the row predates jobs being tied to a work request, so
 * there is definitionally nothing coming to resume it.
 */
data class AbandonedJob(val jobId: Long, val workId: String?)
