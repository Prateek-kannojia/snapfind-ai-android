package com.example.snapfindai.spike

import kotlin.math.max
import kotlin.math.min

/**
 * Phase A of the on-device plan: SCRFD's raw ONNX output decoded into real
 * face boxes + 5-point landmarks. Ported line-for-line from insightface's
 * own scrfd.py (SCRFD.forward / SCRFD.detect / SCRFD.nms) — same strides,
 * same anchor duplication order, same distance-to-box/kps math, same NMS,
 * same defaults (det_thresh=0.5, nms_thresh=0.4). Deliberately not
 * reinvented: this is the one place a subtle difference from production
 * would silently change every downstream result.
 */
data class DetectedFace(
    val box: FloatArray,      // x1, y1, x2, y2 — original image pixel coords
    val score: Float,
    val kps: Array<FloatArray>, // 5 points, each [x, y] — original image pixel coords
)

object ScrfdDecoder {
    private val STRIDES = intArrayOf(8, 16, 32)
    private const val NUM_ANCHORS = 2
    const val DET_THRESH = 0.5f
    const val NMS_THRESH = 0.4f

    /**
     * outputs: the 9 det_500m output tensors, in the ONNX graph's own order —
     * [scores_s8, scores_s16, scores_s32, bbox_s8, bbox_s16, bbox_s32, kps_s8, kps_s16, kps_s32].
     * inputW/inputH: the detector's letterboxed input size (e.g. 800x800).
     * detScale: new_height / original_height from the letterbox resize —
     * boxes/kps come out in letterboxed-image pixels and must be divided by
     * this to land back in the original photo's pixel space.
     */
    fun decode(
        outputs: List<FloatArray>,
        inputW: Int,
        inputH: Int,
        detScale: Float,
    ): List<DetectedFace> {
        val candidates = mutableListOf<DetectedFace>()

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
                        if (score >= DET_THRESH) {
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
                            candidates += DetectedFace(floatArrayOf(x1, y1, x2, y2), score, kps)
                        }
                        k++
                    }
                }
            }
        }

        val scaled = candidates.map { f ->
            DetectedFace(
                floatArrayOf(f.box[0] / detScale, f.box[1] / detScale, f.box[2] / detScale, f.box[3] / detScale),
                f.score,
                f.kps.map { floatArrayOf(it[0] / detScale, it[1] / detScale) }.toTypedArray(),
            )
        }
        return nms(scaled.sortedByDescending { it.score })
    }

    /** Greedy NMS, +1 area convention matched to insightface's own nms() exactly. */
    private fun nms(faces: List<DetectedFace>): List<DetectedFace> {
        val areas = faces.map { (it.box[2] - it.box[0] + 1) * (it.box[3] - it.box[1] + 1) }
        val suppressed = BooleanArray(faces.size)
        val kept = mutableListOf<DetectedFace>()

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
                if (overlap > NMS_THRESH) suppressed[j] = true
            }
        }
        return kept
    }
}