package com.example.snapfindai.facesdk.detector

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DetectedFace/FaceLandmarks wrap android.graphics.RectF/PointF, so this
 * runs under Robolectric rather than as a plain JVM test.
 *
 * Builds minimal, deliberately tiny (16x16) fake SCRFD output tensors with
 * one known detection planted at a known anchor, and checks decode()
 * recovers exactly the box/score/landmarks that anchor implies -- the same
 * kind of "does the port actually compute the right numbers" check used
 * throughout this project, just against a synthetic input instead of a
 * real photo (which would require the actual det_500m.onnx model to
 * produce raw scores/offsets).
 */
@RunWith(RobolectricTestRunner::class)
class ScrfdPostprocessTest {

    // inputW=inputH=16 -> stride8 grid is 2x2 (4 cells x 2 anchors = 8 slots),
    // stride16 grid is 1x1 (2 slots), stride32 grid is 0x0 (no slots at all,
    // since 16/32==0) -- small enough to hand-write every value.
    private val inputSize = 16

    private fun emptyOutputs(
        stride8Scores: FloatArray,
        stride8Boxes: FloatArray,
        stride8Kps: FloatArray,
    ): List<FloatArray> = listOf(
        stride8Scores, FloatArray(2), FloatArray(0),         // scores: s8, s16, s32
        stride8Boxes, FloatArray(8), FloatArray(0),          // bbox:   s8, s16, s32
        stride8Kps, FloatArray(20), FloatArray(0),           // kps:    s8, s16, s32
    )

    @Test
    fun `decodes a single planted detection at the expected box`() {
        // Anchor k=0 is cell (x=0, y=0), a=0 -> center (cx, cy) = (0, 0).
        val scores = FloatArray(8) { if (it == 0) 0.9f else 0.1f } // only k=0 clears DET_THRESH (0.5)
        val boxes = FloatArray(32).also { it[0] = 1f; it[1] = 1f; it[2] = 1f; it[3] = 1f }
        val kps = FloatArray(80) // all zero -> every landmark lands on the anchor center (0, 0)

        val faces = ScrfdPostprocess.decode(
            emptyOutputs(scores, boxes, kps), inputSize, inputSize, detScale = 1f,
        )

        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(0.9f, face.score, 1e-6f)
        // x1 = cx - dx1*stride = 0 - 1*8 = -8, same pattern for the other 3 edges.
        assertEquals(-8f, face.box.left, 1e-4f)
        assertEquals(-8f, face.box.top, 1e-4f)
        assertEquals(8f, face.box.right, 1e-4f)
        assertEquals(8f, face.box.bottom, 1e-4f)
        assertEquals(5, face.landmarks.points.size)
        face.landmarks.points.forEach {
            assertEquals(0f, it.x, 1e-4f)
            assertEquals(0f, it.y, 1e-4f)
        }
    }

    @Test
    fun `detScale maps the box back into original-image pixels`() {
        val scores = FloatArray(8) { if (it == 0) 0.9f else 0.1f }
        val boxes = FloatArray(32).also { it[0] = 1f; it[1] = 1f; it[2] = 1f; it[3] = 1f }
        val kps = FloatArray(80)

        // detScale=0.5 means the letterboxed image was half the original size,
        // so decoded coordinates must be divided by 0.5 (doubled) to land back
        // in the original photo's pixel space.
        val faces = ScrfdPostprocess.decode(
            emptyOutputs(scores, boxes, kps), inputSize, inputSize, detScale = 0.5f,
        )

        val face = faces.single()
        assertEquals(-16f, face.box.left, 1e-4f)
        assertEquals(16f, face.box.right, 1e-4f)
    }

    @Test
    fun `nms suppresses a heavily-overlapping lower-score duplicate`() {
        // k=0 and k=1 are the two anchors of the SAME cell (x=0, y=0), so they
        // share (cx, cy) = (0, 0) -- identical box deltas below means identical
        // boxes, which NMS must collapse to just the higher-scoring one.
        val scores = FloatArray(8).also { it[0] = 0.9f; it[1] = 0.8f }
        val boxes = FloatArray(32).also {
            for (k in 0..1) for (i in 0..3) it[k * 4 + i] = 1f
        }
        val kps = FloatArray(80)

        val faces = ScrfdPostprocess.decode(
            emptyOutputs(scores, boxes, kps), inputSize, inputSize, detScale = 1f,
        )

        assertEquals(1, faces.size)
        assertEquals(0.9f, faces.single().score, 1e-6f)
    }

    @Test
    fun `no anchor clearing the detection threshold means no detections`() {
        val scores = FloatArray(8) { 0.1f }
        val faces = ScrfdPostprocess.decode(
            emptyOutputs(scores, FloatArray(32), FloatArray(80)), inputSize, inputSize, detScale = 1f,
        )
        assertEquals(0, faces.size)
    }

    @Test
    fun `a custom detThreshold changes which anchors clear the bar -- proves FaceDetectorConfig actually reaches this math`() {
        val scores = FloatArray(8) { if (it == 0) 0.3f else 0.1f } // below the 0.5 default, above a lowered 0.2
        val outputs = emptyOutputs(scores, FloatArray(32), FloatArray(80))

        val atDefault = ScrfdPostprocess.decode(outputs, inputSize, inputSize, detScale = 1f)
        assertEquals(0, atDefault.size)

        val atLoweredThreshold = ScrfdPostprocess.decode(outputs, inputSize, inputSize, detScale = 1f, detThreshold = 0.2f)
        assertEquals(1, atLoweredThreshold.size)
    }

    @Test
    fun `a custom nmsThreshold changes whether an overlapping duplicate survives`() {
        val scores = FloatArray(8).also { it[0] = 0.9f; it[1] = 0.8f }
        val boxes = FloatArray(32).also {
            for (k in 0..1) for (i in 0..3) it[k * 4 + i] = 1f // identical boxes -> 100% overlap
        }
        val outputs = emptyOutputs(scores, boxes, FloatArray(80))

        val atDefault = ScrfdPostprocess.decode(outputs, inputSize, inputSize, detScale = 1f)
        assertEquals("default nmsThreshold (0.4) suppresses the 100%-overlapping duplicate", 1, atDefault.size)

        val nmsDisabled = ScrfdPostprocess.decode(outputs, inputSize, inputSize, detScale = 1f, nmsThreshold = 1.0f)
        assertEquals("overlap must be > threshold to suppress; nothing overlaps more than 100%", 2, nmsDisabled.size)
    }
}
