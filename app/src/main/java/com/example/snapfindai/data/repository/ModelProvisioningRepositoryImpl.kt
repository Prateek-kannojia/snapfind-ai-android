package com.example.snapfindai.data.repository

import android.content.Context
import com.example.snapfindai.data.ModelConfig
import com.example.snapfindai.domain.repository.ModelProvisioningRepository
import com.example.snapfindai.facesdk.ModelDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelProvisioningRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelProvisioningRepository {

    override suspend fun areModelsReady(): Boolean =
        ModelDownloader.isCached(context, ModelConfig.DETECTOR_MODEL_FILE_NAME, ModelConfig.DETECTOR_MODEL_SHA256) &&
            ModelDownloader.isCached(context, ModelConfig.EMBEDDER_MODEL_FILE_NAME, ModelConfig.EMBEDDER_MODEL_SHA256)

    override suspend fun downloadModels(onProgress: (progress: Float) -> Unit) {
        val fileCount = 2f
        ModelDownloader.getOrDownload(
            context, ModelConfig.DETECTOR_MODEL_URL, ModelConfig.DETECTOR_MODEL_FILE_NAME, ModelConfig.DETECTOR_MODEL_SHA256,
        ) { downloaded, total -> onProgress((0f + fractionOf(downloaded, total)) / fileCount) }

        ModelDownloader.getOrDownload(
            context, ModelConfig.EMBEDDER_MODEL_URL, ModelConfig.EMBEDDER_MODEL_FILE_NAME, ModelConfig.EMBEDDER_MODEL_SHA256,
        ) { downloaded, total -> onProgress((1f + fractionOf(downloaded, total)) / fileCount) }
    }

    private fun fractionOf(downloaded: Long, total: Long): Float =
        if (total > 0) downloaded.toFloat() / total else 0f
}
