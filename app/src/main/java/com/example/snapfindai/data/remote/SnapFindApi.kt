package com.example.snapfindai.data.remote

import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

// --- Data classes (must match backend JSON field names exactly) ---

data class UploadJobResponse(
    val job_id: String,
    val status: String,
    val selfie_filename: String,
    val event_photo_count: Int,
    val created_at: String
)

// Both GET /jobs/{id} and POST /jobs/{id}/process return this same shape
data class JobSummaryResponse(
    val job_id: String,
    val status: String,
    val selfie_filename: String,
    val event_photo_count: Int,
    val matched_photo_count: Int,
    val created_at: String
)

data class MatchItem(
    val id: Int,
    val event_photo_id: Int,
    val filename: String,
    val match_distance: Double,   // backend sends "match_distance", not "distance"
    val download_url: String,     // relative path e.g. /jobs/{id}/matches/{id}/download
    val created_at: String
)

data class MatchesResponse(
    val job_id: String,
    val status: String,
    val match_count: Int,
    val matches: List<MatchItem>
)

// --- API Interface ---

interface SnapFindApi {

    @Multipart
    @POST("jobs/upload")
    suspend fun uploadJob(
        @Part selfie: MultipartBody.Part,
        @Part eventPhotosZip: MultipartBody.Part
    ): UploadJobResponse

    @GET("jobs/{job_id}")
    suspend fun getJobStatus(
        @Path("job_id") jobId: String
    ): JobSummaryResponse

    @POST("jobs/{job_id}/process")
    suspend fun processJob(
        @Path("job_id") jobId: String,
        @Query("threshold") threshold: Double = 0.5
    ): JobSummaryResponse

    @GET("jobs/{job_id}/matches")
    suspend fun getJobMatches(
        @Path("job_id") jobId: String
    ): MatchesResponse
}