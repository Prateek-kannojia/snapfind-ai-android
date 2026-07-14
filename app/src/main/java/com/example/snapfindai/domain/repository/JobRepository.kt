package com.example.snapfindai.domain.repository

import com.example.snapfindai.data.remote.JobSummaryResponse
import com.example.snapfindai.data.remote.MatchItem
import com.example.snapfindai.data.remote.UploadJobResponse
import java.io.File

// Each method does exactly one thing: one API call, no logic, no orchestration.
interface JobRepository {
    suspend fun uploadJob(selfie: File, zip: File): UploadJobResponse
    suspend fun triggerProcessing(jobId: String, threshold: Double): JobSummaryResponse
    suspend fun getJobStatus(jobId: String): JobSummaryResponse
    suspend fun getJobMatches(jobId: String): List<MatchItem>
}