package com.example.snapfindai.domain.repository

/** Whether the on-device face-matching models are downloaded and cached, and fetching them if not -- separate from FaceMatchRepository so onboarding can check/trigger this without touching the matching engine itself. */
interface ModelProvisioningRepository {
    suspend fun areModelsReady(): Boolean

    /**
     * [onProgress] reports overall progress across both model files, 0f..1f.
     *
     * Resumes rather than restarts: bytes left behind by an interrupted
     * attempt are picked up where they stopped, which is what makes pausing
     * the download worth offering at all.
     */
    suspend fun downloadModels(onProgress: (progress: Float) -> Unit)

    /**
     * Drops the bytes an interrupted download left behind, so the next
     * attempt starts from zero.
     *
     * Pausing keeps them on purpose. This is for the user cancelling
     * outright, where leaving most of a 16 MB download in their storage for
     * a retry that may never come is the wrong trade.
     */
    suspend fun discardPartialDownloads()
}
