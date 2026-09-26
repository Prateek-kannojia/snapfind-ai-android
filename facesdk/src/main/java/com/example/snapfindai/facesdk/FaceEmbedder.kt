package com.example.snapfindai.facesdk

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.internal.OrtSessions
import com.example.snapfindai.facesdk.model.FaceEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.FloatBuffer

/** Embeds an aligned 112x112 face crop (see [FaceAligner.align]) into a 512-D vector. Backed by w600k_mbf, the same embedder production runs. */
interface FaceEmbedder : Closeable {
    suspend fun embed(alignedFace: Bitmap): FaceEmbedding

    companion object {
        /** Loads w600k_mbf.onnx from this app's assets/models/. */
        suspend fun create(context: Context): FaceEmbedder = withContext(Dispatchers.Default) {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSessions.load(context, env, "w600k_mbf.onnx")
            OnnxFaceEmbedder(env, session)
        }
    }
}

private class OnnxFaceEmbedder(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : FaceEmbedder {

    companion object {
        private const val INPUT_NAME = "input.1"
        private const val SIZE = FaceAligner.IMAGE_SIZE
    }

    override suspend fun embed(alignedFace: Bitmap): FaceEmbedding = withContext(Dispatchers.Default) {
        val tensor = preprocess(alignedFace)
        var values = FloatArray(0)
        session.run(mapOf(INPUT_NAME to tensor)).use { result ->
            for (entry in result) {
                val out = entry.value as OnnxTensor
                val buf = out.floatBuffer
                values = FloatArray(buf.remaining())
                buf.get(values)
            }
        }
        tensor.close()
        FaceEmbedding(values)
    }

    /**
     * w600k_mbf preprocessing, validated bit-identical against insightface's
     * ArcFaceONNX.get_feat(): (x - 127.5) / 127.5, BGR->RGB swap, 112x112 NCHW.
     */
    private fun preprocess(bitmap: Bitmap): OnnxTensor {
        val scaled = if (bitmap.width == SIZE && bitmap.height == SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        }

        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)

        val plane = SIZE * SIZE
        val rArr = FloatArray(plane)
        val gArr = FloatArray(plane)
        val bArr = FloatArray(plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            rArr[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f
            gArr[i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f
            bArr[i] = ((p and 0xFF) - 127.5f) / 127.5f
        }
        val buffer = FloatBuffer.allocate(3 * plane)
        buffer.put(rArr); buffer.put(gArr); buffer.put(bArr)
        buffer.rewind()

        if (scaled !== bitmap) scaled.recycle()

        return OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
    }

    override fun close() = session.close()
}
