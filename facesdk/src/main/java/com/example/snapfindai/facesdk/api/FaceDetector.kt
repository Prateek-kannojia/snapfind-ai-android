package com.example.snapfindai.facesdk.api

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.detector.ScrfdFaceDetector
import com.example.snapfindai.facesdk.internal.OrtSessions
import com.example.snapfindai.facesdk.model.DetectedFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Finds faces (box + 5 landmarks) in a decoded bitmap. The only shipped
 * implementation is SCRFD (det_500m) — the same detector production runs —
 * but this is a plain interface: a host app can implement its own (a
 * different architecture, a cloud call, a fake for tests) and hand it to
 * [com.example.snapfindai.facesdk.FaceMatchEngine.create].
 */
interface FaceDetector : Closeable {
    suspend fun detect(bitmap: Bitmap): List<DetectedFace>

    companion object {
        /** The bundled, validated det_500m.onnx from this module's own assets. */
        suspend fun create(context: Context): FaceDetector = createFromAsset(context, "models/det_500m.onnx")

        /**
         * The same SCRFD implementation, loading a model from a different
         * asset path — e.g. a newer version of det_500m the host app bundles
         * itself. Reuses the real, validated preprocessing/decoding code;
         * nothing to reimplement, just point it at a different file.
         */
        suspend fun createFromAsset(context: Context, assetPath: String): FaceDetector =
            withContext(Dispatchers.Default) {
                val env = OrtEnvironment.getEnvironment()
                val session = OrtSessions.fromAsset(context, env, assetPath)
                ScrfdFaceDetector(env, session)
            }

        /**
         * The same SCRFD implementation, loading a model from raw bytes —
         * from anywhere: downloaded at runtime, decrypted, a resource, a
         * different asset layout entirely. Must be an SCRFD-shaped ONNX
         * graph (same inputs/outputs as det_500m); a different detector
         * architecture needs its own [FaceDetector] implementation instead.
         */
        suspend fun createFromBytes(modelBytes: ByteArray): FaceDetector = withContext(Dispatchers.Default) {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSessions.fromBytes(env, modelBytes)
            ScrfdFaceDetector(env, session)
        }
    }
}
