package com.example.snapfindai.facesdk.model

import android.graphics.RectF

/** One face found by [com.example.snapfindai.facesdk.api.FaceDetector.detect], in the source image's pixel space. */
data class DetectedFace(
    val box: RectF,
    val score: Float,
    val landmarks: FaceLandmarks,
)
