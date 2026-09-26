package com.example.snapfindai.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Phase B of the on-device plan: insightface's face_align.norm_crop, ported.
 * Fits a similarity transform (rotation + uniform scale + translation, no
 * reflection) from the 5 detected landmarks to ArcFace's fixed reference
 * points, then warps the ORIGINAL image into the 112x112 aligned crop the
 * embedder expects.
 *
 * insightface computes the transform via skimage's SimilarityTransform,
 * which is a general SVD-based (Umeyama) solver. For exactly 5 non-collinear
 * 2D points -- which real facial landmarks always are -- that reduces to the
 * standard closed-form least-squares fit for a similarity transform (treat
 * each point as a complex number; the best-fit scale+rotation is a single
 * complex division of the centered cross-correlation by the centered
 * source variance). Same unique answer as skimage for this well-posed case,
 * without needing a general SVD routine.
 */
object FaceAligner {
    // insightface's face_align.py arcface_dst, for image_size=112
    // (ratio = 112/112 = 1.0, diff_x = 0 -- the "% 112 == 0" branch).
    private val ARCFACE_DST = arrayOf(
        floatArrayOf(38.2946f, 51.6963f),
        floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f),
        floatArrayOf(70.7299f, 92.2041f),
    )
    const val IMAGE_SIZE = 112

    /** Returns (a, b, tx, ty) for x' = a*x - b*y + tx ; y' = b*x + a*y + ty. */
    private fun estimateSimilarity(src: Array<FloatArray>, dst: Array<FloatArray>): FloatArray {
        val n = src.size
        var srcMeanX = 0f; var srcMeanY = 0f
        var dstMeanX = 0f; var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += src[i][0]; srcMeanY += src[i][1]
            dstMeanX += dst[i][0]; dstMeanY += dst[i][1]
        }
        srcMeanX /= n; srcMeanY /= n; dstMeanX /= n; dstMeanY /= n

        var num1 = 0f; var num2 = 0f; var den = 0f
        for (i in 0 until n) {
            val xc = src[i][0] - srcMeanX
            val yc = src[i][1] - srcMeanY
            val xc2 = dst[i][0] - dstMeanX
            val yc2 = dst[i][1] - dstMeanY
            num1 += xc * xc2 + yc * yc2
            num2 += xc * yc2 - yc * xc2
            den += xc * xc + yc * yc
        }
        val a = num1 / den
        val b = num2 / den
        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)
        return floatArrayOf(a, b, tx, ty)
    }

    /**
     * source: the ORIGINAL (or crop-source) image, same convention as
     * production's crop-from-original. kps: the detected face's 5 landmarks,
     * in that same image's pixel coordinates (ScrfdDecoder already returns
     * them post-detScale, so no further scaling needed here).
     */
    fun alignFace(source: Bitmap, kps: Array<FloatArray>): Bitmap {
        val (a, b, tx, ty) = estimateSimilarity(kps, ARCFACE_DST)

        val matrix = Matrix()
        // Android's row-major order: [scaleX, skewX, transX, skewY, scaleY, transY, 0, 0, 1]
        // -- same forward src->dst convention as cv2.warpAffine's M.
        matrix.setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f))

        val output = Bitmap.createBitmap(IMAGE_SIZE, IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK) // matches cv2.warpAffine's borderValue=0.0
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]
    private operator fun FloatArray.component3() = this[2]
    private operator fun FloatArray.component4() = this[3]
}
