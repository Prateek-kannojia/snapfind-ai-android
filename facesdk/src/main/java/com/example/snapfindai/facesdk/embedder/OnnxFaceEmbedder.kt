package com.example.snapfindai.facesdk.embedder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.FaceAligner
import com.example.snapfindai.facesdk.InvalidModelException
import com.example.snapfindai.facesdk.api.FaceEmbedder
import com.example.snapfindai.facesdk.model.FaceEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * w600k_mbf, run through ONNX Runtime. This is the one and only
 * implementation of [FaceEmbedder] the SDK ships — created via
 * [FaceEmbedder.create]/[FaceEmbedder.createFromBytes], never directly, so
 * a host app never depends on this class name, only the interface.
 */
internal class OnnxFaceEmbedder(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : FaceEmbedder {

    companion object {
        internal const val INPUT_NAME = "input.1"
        private const val SIZE = FaceAligner.IMAGE_SIZE
        internal const val EXPECTED_EMBEDDING_SIZE = 512L

        /**
         * Pure shape check, separated from session creation so it's unit
         * testable without a real OrtSession -- ONNX Runtime's native
         * library is Android-only and can't load inside a desktop JVM test.
         * [outputShape] is the last dimension of the sole output tensor, or
         * null if that couldn't be determined (a dynamic/unreported shape
         * isn't itself an error -- only a *known-wrong* size is rejected).
         */
        internal fun validateShape(inputNames: Set<String>, outputCount: Int, outputShape: Long?) {
            if (INPUT_NAME !in inputNames) {
                throw InvalidModelException(
                    "FaceEmbedder expects an input named '$INPUT_NAME', but this model's inputs " +
                        "are: $inputNames. If this is a different embedder architecture, implement " +
                        "FaceEmbedder yourself instead of loading it through createFromAsset/createFromBytes."
                )
            }
            if (outputCount != 1) {
                throw InvalidModelException(
                    "FaceEmbedder expects exactly 1 output tensor (the embedding), but this " +
                        "model has $outputCount."
                )
            }
            if (outputShape != null && outputShape != -1L && outputShape != EXPECTED_EMBEDDING_SIZE) {
                throw InvalidModelException(
                    "FaceEmbedder expects a $EXPECTED_EMBEDDING_SIZE-D embedding output, but this " +
                        "model produces a ${outputShape}-D one. Downstream cosine-distance " +
                        "comparisons assume every embedding this SDK produces is the same length."
                )
            }
        }
    }

    init {
        val outputInfos = session.outputInfo.values
        val tensorInfo = outputInfos.singleOrNull()?.info as? TensorInfo
        validateShape(session.inputNames, outputInfos.size, tensorInfo?.shape?.lastOrNull())
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
