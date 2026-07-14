package com.example.snapfindai.data.repository

import com.example.snapfindai.data.remote.JobSummaryResponse
import com.example.snapfindai.data.remote.MatchItem
import com.example.snapfindai.data.remote.SnapFindApi
import com.example.snapfindai.data.remote.UploadJobResponse
import com.example.snapfindai.domain.repository.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Named

// Repository only fetches and saves data. One method = one API call.
// No polling, no orchestration, no business decisions — that belongs in the use case.
class JobRepositoryImpl @Inject constructor(
    private val api: SnapFindApi,
    @Named("baseUrl") private val baseUrl: String
) : JobRepository {

    override suspend fun uploadJob(selfie: File, zip: File): UploadJobResponse {
        return withContext(Dispatchers.IO) {
            val selfiePart = MultipartBody.Part.createFormData(
                "selfie", selfie.name,
                selfie.asRequestBody("image/*".toMediaTypeOrNull())
            )
            val zipPart = MultipartBody.Part.createFormData(
                "event_photos_zip", zip.name,
                zip.asRequestBody("application/zip".toMediaTypeOrNull())
            )
            api.uploadJob(selfiePart, zipPart)
        }
    }

    override suspend fun triggerProcessing(jobId: String, threshold: Double): JobSummaryResponse {
        return api.processJob(jobId, threshold)
    }

    override suspend fun getJobStatus(jobId: String): JobSummaryResponse {
        return api.getJobStatus(jobId)
    }

    // Relative download URLs from the backend (e.g. /jobs/x/matches/1/download)
    // are made absolute here so callers never need to know about the base URL.
    override suspend fun getJobMatches(jobId: String): List<MatchItem> {
        val prefix = baseUrl.trimEnd('/')
        return api.getJobMatches(jobId).matches.map { match ->
            match.copy(download_url = "$prefix${match.download_url}")
        }
    }
}