package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.repository.ModelProvisioningRepository
import javax.inject.Inject

class DownloadModelsUseCase @Inject constructor(
    private val modelProvisioningRepository: ModelProvisioningRepository,
) {
    suspend operator fun invoke(onProgress: (progress: Float) -> Unit) =
        modelProvisioningRepository.downloadModels(onProgress)

    /**
     * Throws away what an interrupted download had got through, so the next
     * attempt starts from zero. Pausing keeps those bytes; cancelling
     * shouldn't leave most of a 16 MB download in the user's storage.
     *
     * Kept on this use case rather than given one of its own because it is
     * meaningless apart from the download it undoes.
     */
    suspend fun discardPartialDownloads() = modelProvisioningRepository.discardPartialDownloads()
}
