package com.example.snapfindai.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.facesdk.BitmapDecoder
import com.example.snapfindai.facesdk.FaceMatchEngine
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.facesdk.ModelDownloader
import com.example.snapfindai.facesdk.api.FaceDetector
import com.example.snapfindai.facesdk.api.FaceEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs matching entirely on-device via facesdk. Event photos are decoded
 * and scored ONE AT A TIME, not batch-decoded into a list first: a job's
 * photos can be dozens of full-resolution phone photos, and holding them
 * all as decoded Bitmaps simultaneously risks OOM on a real device. This
 * is exactly the case facesdk's lower-level embedSelfie()/scoreEventPhoto()
 * primitives exist for, instead of the matchJob() convenience batch call.
 */
@Singleton
class OnDeviceFaceMatchRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FaceMatchRepository {

    private val engineMutex = Mutex()
    @Volatile private var engine: FaceMatchEngine? = null

    private companion object {
        // Debug builds bundle these as facesdk/src/debug/assets/models/ for
        // fast local iteration; release builds have no bundled models at
        // all (so the APK isn't ~16MB heavier for every install) and fetch
        // them here instead, on first use, caching them after. Hosted as a
        // GitHub Release asset -- same repo, versioned, free, no backend
        // needed for the app's on-device flow to work.
        private const val MODEL_RELEASE_BASE =
            "https://github.com/Prateek-kannojia/snapfind-ai-android/releases/download/models-v1"
        private const val DETECTOR_MODEL_URL = "$MODEL_RELEASE_BASE/det_500m.onnx"
        private const val EMBEDDER_MODEL_URL = "$MODEL_RELEASE_BASE/w600k_mbf.onnx"
        private const val DETECTOR_MODEL_SHA256 = "5e4447f50245bbd7966bd6c0fa52938c61474a04ec7def48753668a9d8b4ea3a"
        private const val EMBEDDER_MODEL_SHA256 = "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f"
    }

    private suspend fun engine(): FaceMatchEngine =
        engine ?: engineMutex.withLock {
            engine ?: buildEngine().also { engine = it }
        }

    private suspend fun buildEngine(): FaceMatchEngine {
        val detectorFile = ModelDownloader.getOrDownload(
            context, DETECTOR_MODEL_URL, "det_500m.onnx", DETECTOR_MODEL_SHA256,
        )
        val embedderFile = ModelDownloader.getOrDownload(
            context, EMBEDDER_MODEL_URL, "w600k_mbf.onnx", EMBEDDER_MODEL_SHA256,
        )
        val detector = FaceDetector.createFromFile(detectorFile)
        val embedder = FaceEmbedder.createFromFile(embedderFile)
        return FaceMatchEngine.create(detector, embedder)
    }

    override suspend fun matchPhotos(
        selfie: File,
        eventPhotos: List<File>,
        threshold: Float,
    ): List<FaceMatchResult> {
        val engine = engine()
        val selfieBitmap = BitmapDecoder.decodeWithExifCorrection(selfie)
        val selfieEmbedding = try {
            engine.embedSelfie(selfieBitmap)
        } finally {
            selfieBitmap.recycle()
        }

        val matches = mutableListOf<FaceMatchResult>()
        for (photo in eventPhotos) {
            val bitmap: Bitmap = try {
                BitmapDecoder.decodeWithExifCorrection(photo)
            } catch (e: Exception) {
                continue // corrupt/unreadable file -> skip, same as "no face found"
            }
            val distance = try {
                engine.scoreEventPhoto(selfieEmbedding, bitmap)
            } catch (e: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
            if (distance != null && FaceMatcher.isMatch(distance, threshold)) {
                matches += FaceMatchResult(photo = photo, distance = distance)
            }
        }
        return matches
    }
}
