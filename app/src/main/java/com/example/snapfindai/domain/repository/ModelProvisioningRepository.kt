package com.example.snapfindai.domain.repository

/** Whether the on-device face-matching models are downloaded and cached, and fetching them if not -- separate from FaceMatchRepository so onboarding can check/trigger this without touching the matching engine itself. */
interface ModelProvisioningRepository {
    suspend fun areModelsReady(): Boolean

    /** [onProgress] reports overall progress across both model files, 0f..1f. */
    suspend fun downloadModels(onProgress: (progress: Float) -> Unit)
}
