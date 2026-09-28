package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.repository.ModelProvisioningRepository
import javax.inject.Inject

class DownloadModelsUseCase @Inject constructor(
    private val modelProvisioningRepository: ModelProvisioningRepository,
) {
    suspend operator fun invoke(onProgress: (progress: Float) -> Unit) =
        modelProvisioningRepository.downloadModels(onProgress)
}
