package com.example.snapfindai.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.data.ModelConfig
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.domain.repository.PreparedSelfie
import com.example.snapfindai.facesdk.BitmapDecoder
import com.example.snapfindai.facesdk.FaceMatchEngine
import com.example.snapfindai.facesdk.FaceMatcher
import com.example.snapfindai.facesdk.ModelDownloader
import com.example.snapfindai.facesdk.api.FaceDetector
import com.example.snapfindai.facesdk.api.FaceEmbedder
import com.example.snapfindai.facesdk.model.FaceEmbedding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    // Debug builds bundle the models as facesdk/src/debug/assets/models/ for
    // fast local iteration; release builds have no bundled models at all
    // (so the APK isn't ~16MB heavier for every install). Normally the
    // onboarding flow (see ModelProvisioningRepositoryImpl) has already
    // downloaded and cached both files before this is ever called -- this
    // is just a safety net for that not being true (e.g. cleared app
    // storage), so getOrDownload() below is a no-op cache hit in the
    // common case, not a real download.
    private suspend fun engine(): FaceMatchEngine =
        engine ?: engineMutex.withLock {
            engine ?: buildEngine().also { engine = it }
        }

    private suspend fun buildEngine(): FaceMatchEngine {
        val detectorFile = ModelDownloader.getOrDownload(
            context, ModelConfig.DETECTOR_MODEL_URL, ModelConfig.DETECTOR_MODEL_FILE_NAME, ModelConfig.DETECTOR_MODEL_SHA256,
        )
        val embedderFile = ModelDownloader.getOrDownload(
            context, ModelConfig.EMBEDDER_MODEL_URL, ModelConfig.EMBEDDER_MODEL_FILE_NAME, ModelConfig.EMBEDDER_MODEL_SHA256,
        )
        val detector = FaceDetector.createFromFile(detectorFile)
        val embedder = FaceEmbedder.createFromFile(embedderFile)
        return FaceMatchEngine.create(detector, embedder)
    }

    /**
     * The embedding is the whole reason [PreparedSelfie] exists -- computing
     * it means a decode, a detection, an alignment and an embedder pass, and
     * carrying it to [matchPhotos] is what stops all of that happening twice
     * per job. Private, so nothing outside this file can build one or unwrap
     * one: facesdk's types stay out of the domain layer.
     */
    private class EmbeddedSelfie(val embedding: FaceEmbedding) : PreparedSelfie

    override suspend fun prepareSelfie(selfie: File): PreparedSelfie = withContext(Dispatchers.Default) {
        val engine = engine()
        val bitmap = decodeSelfie(selfie)
        val embedding = try {
            engine.embedSelfie(bitmap) // throws NoFaceDetectedException if there's no usable face
        } finally {
            bitmap.recycle()
        }
        EmbeddedSelfie(embedding)
    }

    // Dispatchers.Default, not IO: the expensive parts here are full-resolution
    // bitmap decoding and the rotation copy, which are CPU and allocation
    // bound. facesdk's detect/embed already hop to Default themselves; before
    // this, every decode ran on whatever thread the caller was on -- which was
    // the main thread, and froze the app for the length of the job.
    override suspend fun matchPhotos(
        selfie: PreparedSelfie,
        eventPhotos: List<File>,
        threshold: Float,
        onProgress: ((scored: Int, total: Int) -> Unit)?,
    ): List<FaceMatchResult> = withContext(Dispatchers.Default) {
        val engine = engine()
        // Only prepareSelfie above can produce one of these, so this holds for
        // every caller -- the check is here to fail loudly rather than with a
        // bare ClassCastException if a second implementation ever appears.
        val selfieEmbedding = (selfie as? EmbeddedSelfie)?.embedding
            ?: error("PreparedSelfie came from a different FaceMatchRepository implementation")

        val matches = mutableListOf<FaceMatchResult>()
        eventPhotos.forEachIndexed { index, photo ->
            val bitmap: Bitmap? = try {
                BitmapDecoder.decodeWithExifCorrection(photo)
            } catch (e: Exception) {
                null // corrupt/unreadable file -> skip, same as "no face found"
            }
            if (bitmap != null) {
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
            onProgress?.invoke(index + 1, eventPhotos.size)
        }
        matches
    }

    /**
     * Unlike an event photo, an unreadable selfie can't be skipped -- there's
     * no job without it -- so it gets a message the user can act on instead
     * of the decoder's internal "Could not decode temp_selfie.jpg".
     */
    private fun decodeSelfie(selfie: File): Bitmap = try {
        BitmapDecoder.decodeWithExifCorrection(selfie)
    } catch (e: Exception) {
        throw IOException("We couldn't read your selfie. Please choose it again.", e)
    }
}
