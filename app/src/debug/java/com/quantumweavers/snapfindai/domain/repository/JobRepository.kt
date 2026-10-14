package com.quantumweavers.snapfindai.domain.repository

import com.quantumweavers.snapfindai.data.remote.JobSummaryResponse
import com.quantumweavers.snapfindai.data.remote.MatchItem
import com.quantumweavers.snapfindai.data.remote.UploadJobResponse
import java.io.File

// Each method does exactly one thing: one API call, no logic, no orchestration.
interface JobRepository {
    suspend fun uploadJob(selfie: File, zip: File): UploadJobResponse
    suspend fun triggerProcessing(jobId: String, threshold: Double): JobSummaryResponse
    suspend fun getJobStatus(jobId: String): JobSummaryResponse
    suspend fun getJobMatches(jobId: String): List<MatchItem>
}