package io.github.prateekkannojia.facecore.model

import android.graphics.RectF

/** One face found by [io.github.prateekkannojia.facecore.api.FaceDetector.detect], in the source image's pixel space. */
data class DetectedFace(
    val box: RectF,
    val score: Float,
    val landmarks: FaceLandmarks,
)
