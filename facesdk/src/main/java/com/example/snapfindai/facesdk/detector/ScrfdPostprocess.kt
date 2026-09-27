package com.example.snapfindai.facesdk.detector

import android.graphics.PointF
import android.graphics.RectF
import com.example.snapfindai.facesdk.model.DetectedFace
import com.example.snapfindai.facesdk.model.FaceLandmarks
import kotlin.math.max
import kotlin.math.min

/**
 * SCRFD's raw ONNX output decoded into real face boxes + 5-point landmarks.
 * Ported line-for-line from insightface's own scrfd.py (SCRFD.forward /
 * SCRFD.detect / SCRFD.nms) — same strides, same anchor duplication order,
 * same distance-to-box/kps math, same NMS, same defaults (det_thresh=0.5,
 * nms_thresh=0.4). Deliberately not reinvented: this is the one place a
 * subtle difference from production would silently change every
 * downstream result.
 */
internal object ScrfdPostprocess {
    private val STRIDES = intArrayOf(8, 16, 32)
    private const val NUM_ANCHORS = 2

    private data class RawFace(val box: FloatArray, val score: Float, val kps: Array<FloatArray>)

    /**
     * outputs: the 9 det_500m output tensors, in the ONNX graph's own order —
     * [scores_s8, scores_s16, scores_s32, bbox_s8, bbox_s16, bbox_s32, kps_s8, kps_s16, kps_s32].
     * inputW/inputH: the detector's letterboxed input size (e.g. 800x800).
     * detScale: new_height / original_height from the letterbox resize —
     * boxes/kps come out in letterboxed-image pixels and must be divided by
     * this to land back in the original photo's pixel space.
     * detThreshold/nmsThreshold: see FaceDetectorConfig -- insightface's own
     * SCRFD defaults are 0.5/0.4, kept as this function's defaults too so
     * existing call sites/tests don't need to change to stay correct.
     */
    fun decode(
        outputs: List<FloatArray>,
        inputW: Int,
        inputH: Int,
        detScale: Float,
        detThreshold: Float = 0.5f,
        nmsThreshold: Float = 0.4f,
    ): List<DetectedFace> {
        val candidates = mutableListOf<RawFace>()

        for (idx in STRIDES.indices) {
            val stride = STRIDES[idx]
            val height = inputH / stride
            val width = inputW / stride
            val scores = outputs[idx]
            val bboxPreds = outputs[idx + 3]
            val kpsPreds = outputs[idx + 6]

            var k = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val cx = (x * stride).toFloat()
                    val cy = (y * stride).toFloat()
                    for (a in 0 until NUM_ANCHORS) {
                        val score = scores[k]
                        if (score >= detThreshold) {
                            val bOff = k * 4
                            val x1 = cx - bboxPreds[bOff] * stride
                            val y1 = cy - bboxPreds[bOff + 1] * stride
                            val x2 = cx + bboxPreds[bOff + 2] * stride
                            val y2 = cy + bboxPreds[bOff + 3] * stride

                            val kOff = k * 10
                            val kps = Array(5) { j ->
                                floatArrayOf(
                                    cx + kpsPreds[kOff + j * 2] * stride,
                                    cy + kpsPreds[kOff + j * 2 + 1] * stride,
                                )
                            }
                            candidates += RawFace(floatArrayOf(x1, y1, x2, y2), score, kps)
                        }
                        k++
                    }
                }
            }
        }

        val scaled = candidates.map { f ->
            RawFace(
                floatArrayOf(f.box[0] / detScale, f.box[1] / detScale, f.box[2] / detScale, f.box[3] / detScale),
                f.score,
                f.kps.map { floatArrayOf(it[0] / detScale, it[1] / detScale) }.toTypedArray(),
            )
        }
        return nms(scaled.sortedByDescending { it.score }, nmsThreshold).map { it.toDetectedFace() }
    }

    private fun RawFace.toDetectedFace(): DetectedFace = DetectedFace(
        box = RectF(box[0], box[1], box[2], box[3]),
        score = score,
        landmarks = FaceLandmarks(kps.map { PointF(it[0], it[1]) }.toTypedArray()),
    )

    /** Greedy NMS, +1 area convention matched to insightface's own nms() exactly. */
    private fun nms(faces: List<RawFace>, nmsThreshold: Float): List<RawFace> {
        val areas = faces.map { (it.box[2] - it.box[0] + 1) * (it.box[3] - it.box[1] + 1) }
        val suppressed = BooleanArray(faces.size)
        val kept = mutableListOf<RawFace>()

        for (i in faces.indices) {
            if (suppressed[i]) continue
            kept += faces[i]
            for (j in i + 1 until faces.size) {
                if (suppressed[j]) continue
                val xx1 = max(faces[i].box[0], faces[j].box[0])
                val yy1 = max(faces[i].box[1], faces[j].box[1])
                val xx2 = min(faces[i].box[2], faces[j].box[2])
                val yy2 = min(faces[i].box[3], faces[j].box[3])
                val w = max(0f, xx2 - xx1 + 1)
                val h = max(0f, yy2 - yy1 + 1)
                val inter = w * h
                val overlap = inter / (areas[i] + areas[j] - inter)
                if (overlap > nmsThreshold) suppressed[j] = true
            }
        }
        return kept
    }
}
