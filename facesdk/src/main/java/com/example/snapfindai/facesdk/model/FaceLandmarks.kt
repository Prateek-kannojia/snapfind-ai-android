package com.example.snapfindai.facesdk.model

import android.graphics.PointF

/**
 * The 5 facial landmarks SCRFD detects alongside a face box — left eye,
 * right eye, nose, left mouth corner, right mouth corner, in that order.
 * Coordinates are in the source image's pixel space. This is what
 * [com.example.snapfindai.facesdk.FaceAligner] needs to compute an aligned
 * crop.
 */
class FaceLandmarks(val points: Array<PointF>) {
    init {
        require(points.size == 5) { "FaceLandmarks requires exactly 5 points, got ${points.size}" }
    }

    override fun equals(other: Any?): Boolean =
        other is FaceLandmarks && points.contentEquals(other.points)

    override fun hashCode(): Int = points.contentHashCode()
}
