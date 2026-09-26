package com.example.snapfindai.facesdk.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.example.snapfindai.facesdk.api.FaceDetector
import com.example.snapfindai.facesdk.model.DetectedFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * SCRFD (det_500m), run through ONNX Runtime. This is the one and only
 * implementation of [FaceDetector] the SDK ships — created via
 * [FaceDetector.create]/[FaceDetector.createFromBytes], never directly, so
 * a host app never depends on this class name, only the interface.
 */
internal class ScrfdFaceDetector(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : FaceDetector {

    companion object {
        // Matches Face_recognition/core/settings.py FACE_DETECTOR_SIZE default (800).
        private const val INPUT_SIZE = 800
        private const val INPUT_NAME = "input.1"
    }

    private class Letterboxed(val tensor: OnnxTensor, val detScale: Float, val inputSize: Int)

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> = withContext(Dispatchers.Default) {
        val input = preprocess(bitmap)
        val outputs = mutableListOf<FloatArray>()
        session.run(mapOf(INPUT_NAME to input.tensor)).use { result ->
            for (entry in result) {
                val tensor = entry.value as OnnxTensor
                val buf = tensor.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                outputs += arr
            }
        }
        input.tensor.close()
        ScrfdPostprocess.decode(outputs, input.inputSize, input.inputSize, input.detScale)
    }

    /**
     * insightface's SCRFD preprocessing (scrfd.py:162-163): letterbox into a
     * square [INPUT_SIZE] canvas preserving aspect ratio, (pixel - 127.5) / 128.0,
     * BGR->RGB swap, NCHW. detScale matches insightface's own det_scale
     * (scrfd.py:286): for a square input size this is just newSize/max(w,h) —
     * the ratio needed to map decoded boxes/landmarks back into original
     * image pixels.
     */
    private fun preprocess(bitmap: Bitmap): Letterboxed {
        val size = INPUT_SIZE
        val ratio = size.toFloat() / max(bitmap.width, bitmap.height)
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        val pixels = IntArray(newW * newH)
        scaled.getPixels(pixels, 0, newW, 0, 0, newW, newH)

        val plane = size * size
        val padVal = (0f - 127.5f) / 128.0f
        val rArr = FloatArray(plane) { padVal }
        val gArr = FloatArray(plane) { padVal }
        val bArr = FloatArray(plane) { padVal }

        for (y in 0 until newH) {
            for (x in 0 until newW) {
                val p = pixels[y * newW + x]
                val idx = y * size + x
                rArr[idx] = (((p shr 16) and 0xFF) - 127.5f) / 128.0f
                gArr[idx] = (((p shr 8) and 0xFF) - 127.5f) / 128.0f
                bArr[idx] = ((p and 0xFF) - 127.5f) / 128.0f
            }
        }
        val buffer = FloatBuffer.allocate(3 * plane)
        buffer.put(rArr); buffer.put(gArr); buffer.put(bArr)
        buffer.rewind()

        if (scaled !== bitmap) scaled.recycle()

        val tensor = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, size.toLong(), size.toLong()))
        return Letterboxed(tensor, ratio, size)
    }

    override fun close() = session.close()
}
