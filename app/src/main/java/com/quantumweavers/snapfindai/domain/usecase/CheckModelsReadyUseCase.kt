package com.quantumweavers.snapfindai.domain.usecase

import com.quantumweavers.snapfindai.domain.repository.ModelProvisioningRepository
import javax.inject.Inject

class CheckModelsReadyUseCase @Inject constructor(
    private val modelProvisioningRepository: ModelProvisioningRepository,
) {
    suspend operator fun invoke(): Boolean = modelProvisioningRepository.areModelsReady()
}
