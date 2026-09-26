package com.example.snapfindai.facesdk

import android.graphics.PointF
import com.example.snapfindai.facesdk.model.FaceLandmarks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FaceAligner.align() itself renders through android.graphics.Canvas, which
 * Robolectric's default (non-native) shadow doesn't composite faithfully
 * enough to assert on rendered pixels here -- so the actual risky part of
 * the port, [FaceAligner.estimateSimilarity], is tested directly as pure
 * math instead. align() is still exercised for its API contract (output
 * shape) under Robolectric, since that doesn't depend on real compositing.
 */
@RunWith(RobolectricTestRunner::class)
class FaceAlignerTest {

    // The same 5 reference points ARCFACE_DST holds internally (kept private
    // there) -- a real, well-posed (non-collinear) 5-point face shape.
    private val referencePoints = arrayOf(
        floatArrayOf(38.2946f, 51.6963f),
        floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f),
        floatArrayOf(70.7299f, 92.2041f),
    )

    @Test
    fun `identical src and dst points is the identity transform`() {
        val (a, b, tx, ty) = FaceAligner.estimateSimilarity(referencePoints, referencePoints)
        assertEquals(1f, a, 1e-4f)
        assertEquals(0f, b, 1e-4f)
        assertEquals(0f, tx, 1e-4f)
        assertEquals(0f, ty, 1e-4f)
    }

    @Test
    fun `recovers a known pure scale-plus-translation`() {
        // dst = 2*src + (5, -3), no rotation -- least squares should recover
        // this exactly since the data is generated from exactly this model.
        val dst = referencePoints.map { floatArrayOf(2f * it[0] + 5f, 2f * it[1] - 3f) }.toTypedArray()

        val (a, b, tx, ty) = FaceAligner.estimateSimilarity(referencePoints, dst)

        assertEquals(2f, a, 1e-3f)
        assertEquals(0f, b, 1e-3f)
        assertEquals(5f, tx, 1e-2f)
        assertEquals(-3f, ty, 1e-2f)
    }

    @Test
    fun `recovers a known 90-degree rotation`() {
        // x' = -y, y' = x -- i.e. a=0, b=1, tx=0, ty=0 in the class's own
        // convention (x' = a*x - b*y + tx ; y' = b*x + a*y + ty).
        val dst = referencePoints.map { floatArrayOf(-it[1], it[0]) }.toTypedArray()

        val (a, b, tx, ty) = FaceAligner.estimateSimilarity(referencePoints, dst)

        assertEquals(0f, a, 1e-3f)
        assertEquals(1f, b, 1e-3f)
        assertEquals(0f, tx, 1e-2f)
        assertEquals(0f, ty, 1e-2f)
    }

    @Test
    fun `align always returns a 112x112 crop regardless of source size`() {
        val source = android.graphics.Bitmap.createBitmap(500, 500, android.graphics.Bitmap.Config.ARGB_8888)
        val landmarks = FaceLandmarks(referencePoints.map { PointF(it[0] * 3, it[1] * 3) }.toTypedArray())

        val aligned = FaceAligner.align(source, landmarks)

        assertEquals(FaceAligner.IMAGE_SIZE, aligned.width)
        assertEquals(FaceAligner.IMAGE_SIZE, aligned.height)
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}
