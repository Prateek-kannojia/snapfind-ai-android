package com.example.snapfindai.data

/**
 * Single source of truth for where the on-device face-matching models live
 * and what they must hash to. Shared by [com.example.snapfindai.data.repository.OnDeviceFaceMatchRepositoryImpl]
 * (which loads them, downloading on first use if needed) and
 * [com.example.snapfindai.data.repository.ModelProvisioningRepositoryImpl]
 * (which checks/downloads them ahead of time for onboarding) -- kept in one
 * place so the two can never drift to different URLs or checksums.
 */
internal object ModelConfig {
    private const val MODEL_RELEASE_BASE =
        "https://github.com/Prateek-kannojia/snapfind-ai-android/releases/download/models-v1"

    const val DETECTOR_MODEL_FILE_NAME = "det_500m.onnx"
    const val EMBEDDER_MODEL_FILE_NAME = "w600k_mbf.onnx"
    const val DETECTOR_MODEL_URL = "$MODEL_RELEASE_BASE/$DETECTOR_MODEL_FILE_NAME"
    const val EMBEDDER_MODEL_URL = "$MODEL_RELEASE_BASE/$EMBEDDER_MODEL_FILE_NAME"
    const val DETECTOR_MODEL_SHA256 = "5e4447f50245bbd7966bd6c0fa52938c61474a04ec7def48753668a9d8b4ea3a"
    const val EMBEDDER_MODEL_SHA256 = "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f"
}
