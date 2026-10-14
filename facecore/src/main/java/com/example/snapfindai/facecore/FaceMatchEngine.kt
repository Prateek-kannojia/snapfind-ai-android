package com.example.snapfindai.facesdk

import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.api.FaceDetector
import com.example.snapfindai.facesdk.api.FaceDetectorConfig
import com.example.snapfindai.facesdk.api.FaceEmbedder
import com.example.snapfindai.facesdk.model.DetectedFace
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
 * For composable/advanced use, [detectFaces] and [embedFace] expose the
 * underlying [FaceDetector]/[FaceEmbedder] without handing out the raw,
 * independently-closeable objects — closing one out from under a live
 * engine used to be possible and silently broke it. Call [close] on the
 * engine itself when done; the detector/embedder it owns close with it.
 */
class FaceMatchEngine internal constructor(
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val logger: FaceSdkLogger,
) : Closeable {

    @Volatile private var closed = false

    companion object {
        /** The bundled, validated pair: SCRFD (det_500m.onnx) + w600k_mbf.onnx from this module's own assets. [detectorConfig] tunes the detector's thresholds/input size without needing to assemble the pieces by hand. */
        suspend fun create(
            context: Context,
            logger: FaceSdkLogger = FaceSdkLogger.NONE,
            detectorConfig: FaceDetectorConfig = FaceDetectorConfig(),
        ): FaceMatchEngine {
            val detector = FaceDetector.create(context, detectorConfig)
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

    private fun checkNotClosed() {
        check(!closed) { "FaceMatchEngine is closed -- create a new one instead of reusing a closed instance." }
    }

    /** Every face found in [bitmap] — for composable/advanced use, e.g. letting a user pick which face is "them" in a group photo instead of automatically taking the largest. */
    suspend fun detectFaces(bitmap: Bitmap): List<DetectedFace> {
        checkNotClosed()
        return detector.detect(bitmap)
    }

    /** Embeds an already-aligned 112x112 face crop (see [FaceAligner.align]) — for composable/advanced use. */
    suspend fun embedFace(alignedFace: Bitmap): FaceEmbedding {
        checkNotClosed()
        return embedder.embed(alignedFace)
    }

    /** @throws NoFaceDetectedException if the selfie has no detectable face. */
    suspend fun embedSelfie(selfie: Bitmap): FaceEmbedding {
        checkNotClosed()
        val faces = detector.detect(selfie)
        val largest = faces.maxByOrNull { it.box.width() * it.box.height() }
            ?: throw NoFaceDetectedException("No face detected in selfie")
        val aligned = FaceAligner.align(selfie, largest.landmarks)
        // finally: embed() can throw, and the aligned crop is ours to free
        // either way. Left to the garbage collector it would be reclaimed
        // eventually, but "eventually" is the wrong answer under the memory
        // pressure that caused the throw in the first place.
        return try {
            embedder.embed(aligned)
        } finally {
            aligned.recycle()
        }
    }

    /** Null means no face was found in the photo — a valid outcome, not an error. */
    suspend fun scoreEventPhoto(selfieEmbedding: FaceEmbedding, eventPhoto: Bitmap): Float? {
        checkNotClosed()
        val faces = detector.detect(eventPhoto)
        if (faces.isEmpty()) return null
        // Same finally as embedSelfie, and it matters more here: this runs
        // once per face per photo, so a group shot across a few hundred
        // photos is thousands of crops. A caller scoring a batch treats a
        // throw as "no match" and keeps going, so anything systematic would
        // otherwise leak on every one of them.
        return faces.minOf { face ->
            val aligned = FaceAligner.align(eventPhoto, face.landmarks)
            val embedding = try {
                embedder.embed(aligned)
            } finally {
                aligned.recycle()
            }
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
        checkNotClosed()
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
        closed = true
        detector.close()
        embedder.close()
    }
}
