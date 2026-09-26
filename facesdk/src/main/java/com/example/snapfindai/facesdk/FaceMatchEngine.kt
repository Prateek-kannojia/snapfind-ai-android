package com.example.snapfindai.facesdk

import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.model.EventPhotoInput
import com.example.snapfindai.facesdk.model.EventPhotoMatchResult
import com.example.snapfindai.facesdk.model.FaceEmbedding
import java.io.Closeable

/**
 * The high-level entry point: give it a selfie and a job's event photos,
 * get back match results. Mirrors the backend's face_matcher.py job-level
 * rules exactly, so the same job scores the same way on-device and on the
 * server:
 *  - selfie: the LARGEST detected face wins (matches _selfie_embedding_scrfd).
 *  - event photo: matches if ANY detected face is within threshold — the
 *    closest one determines the distance (matches _embed_and_score_event_photo,
 *    "a photo matches if anyone in it matches").
 *
 * For composable/advanced use, [FaceDetector], [FaceAligner], [FaceEmbedder]
 * and [FaceMatcher] are also public and usable independently of this facade.
 */
class FaceMatchEngine internal constructor(
    /** The underlying detector, for composable/advanced use — e.g. inspecting every face in a photo rather than just the match decision. */
    val detector: FaceDetector,
    /** The underlying embedder, for composable/advanced use. */
    val embedder: FaceEmbedder,
    private val logger: FaceSdkLogger,
) : Closeable {

    companion object {
        /** The bundled, validated pair: SCRFD (det_500m.onnx) + w600k_mbf.onnx from this module's own assets. */
        suspend fun create(context: Context, logger: FaceSdkLogger = FaceSdkLogger.NONE): FaceMatchEngine {
            val detector = FaceDetector.create(context)
            val embedder = FaceEmbedder.create(context)
            return FaceMatchEngine(detector, embedder, logger)
        }

        /**
         * Bring your own detector and/or embedder — e.g. a different ONNX
         * model, a remote-inference implementation, or a fake for tests.
         * Both are just the [FaceDetector]/[FaceEmbedder] interfaces, so
         * anything conforming to them works here.
         */
        fun create(
            detector: FaceDetector,
            embedder: FaceEmbedder,
            logger: FaceSdkLogger = FaceSdkLogger.NONE,
        ): FaceMatchEngine = FaceMatchEngine(detector, embedder, logger)
    }

    /** @throws NoFaceDetectedException if the selfie has no detectable face. */
    suspend fun embedSelfie(selfie: Bitmap): FaceEmbedding {
        val faces = detector.detect(selfie)
        val largest = faces.maxByOrNull { it.box.width() * it.box.height() }
            ?: throw NoFaceDetectedException("No face detected in selfie")
        val aligned = FaceAligner.align(selfie, largest.landmarks)
        val embedding = embedder.embed(aligned)
        aligned.recycle()
        return embedding
    }

    /** Null means no face was found in the photo — a valid outcome, not an error. */
    suspend fun scoreEventPhoto(selfieEmbedding: FaceEmbedding, eventPhoto: Bitmap): Float? {
        val faces = detector.detect(eventPhoto)
        if (faces.isEmpty()) return null
        return faces.minOf { face ->
            val aligned = FaceAligner.align(eventPhoto, face.landmarks)
            val embedding = embedder.embed(aligned)
            aligned.recycle()
            FaceMatcher.distance(selfieEmbedding, embedding)
        }
    }

    /**
     * @throws NoFaceDetectedException if the selfie has no detectable face.
     * An individual event photo failing to decode/detect (corrupt file,
     * unexpected format) does not abort the batch — it scores as no match,
     * the same outcome as a photo where no face was found.
     */
    suspend fun matchJob(
        selfie: Bitmap,
        eventPhotos: List<EventPhotoInput>,
        threshold: Float = FaceMatcher.DEFAULT_THRESHOLD,
    ): List<EventPhotoMatchResult> {
        val selfieEmbedding = embedSelfie(selfie)
        return eventPhotos.map { photo ->
            val distance = try {
                scoreEventPhoto(selfieEmbedding, photo.bitmap)
            } catch (e: Exception) {
                logger.onError("scoreEventPhoto failed for event photo '${photo.id}', scoring as no match", e)
                null
            }
            EventPhotoMatchResult(
                id = photo.id,
                distance = distance,
                isMatch = distance != null && FaceMatcher.isMatch(distance, threshold),
            )
        }
    }

    override fun close() {
        detector.close()
        embedder.close()
    }
}
