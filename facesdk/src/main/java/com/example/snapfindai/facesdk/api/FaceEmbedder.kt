package com.example.snapfindai.facesdk.api

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.embedder.OnnxFaceEmbedder
import com.example.snapfindai.facesdk.internal.OrtSessions
import com.example.snapfindai.facesdk.model.FaceEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Embeds an aligned 112x112 face crop (see
 * [com.example.snapfindai.facesdk.FaceAligner.align]) into a 512-D vector.
 * The only shipped implementation is w600k_mbf — the same embedder
 * production runs — but this is a plain interface: a host app can implement
 * its own and hand it to [com.example.snapfindai.facesdk.FaceMatchEngine.create].
 */
interface FaceEmbedder : Closeable {
    suspend fun embed(alignedFace: Bitmap): FaceEmbedding

    companion object {
        /** The bundled, validated w600k_mbf.onnx from this module's own assets. */
        suspend fun create(context: Context): FaceEmbedder = createFromAsset(context, "models/w600k_mbf.onnx")

        /**
         * The same w600k_mbf implementation, loading a model from a
         * different asset path — e.g. a newer version the host app bundles
         * itself. Reuses the real, validated preprocessing code; nothing to
         * reimplement, just point it at a different file.
         */
        suspend fun createFromAsset(context: Context, assetPath: String): FaceEmbedder =
            withContext(Dispatchers.Default) {
                val env = OrtEnvironment.getEnvironment()
                val session = OrtSessions.fromAsset(context, env, assetPath)
                OnnxFaceEmbedder(env, session)
            }

        /**
         * The same w600k_mbf implementation, loading a model from raw bytes
         * — from anywhere: downloaded at runtime, decrypted, a resource, a
         * different asset layout entirely. Must produce a 512-D embedding
         * from a 112x112 input the same way w600k_mbf does; a different
         * embedder architecture needs its own [FaceEmbedder] implementation.
         */
        suspend fun createFromBytes(modelBytes: ByteArray): FaceEmbedder = withContext(Dispatchers.Default) {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSessions.fromBytes(env, modelBytes)
            OnnxFaceEmbedder(env, session)
        }

        /**
         * The same w600k_mbf implementation, loading a model from a local
         * file — e.g. one downloaded and cached at runtime (see
         * [com.example.snapfindai.facesdk.ModelDownloader]). Loads straight
         * from disk rather than buffering the whole file into memory first,
         * which matters for a model this size.
         */
        suspend fun createFromFile(file: File): FaceEmbedder = withContext(Dispatchers.Default) {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSessions.fromFile(env, file.absolutePath)
            OnnxFaceEmbedder(env, session)
        }
    }
}
