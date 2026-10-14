package com.example.snapfindai.facecore.model

import android.graphics.RectF

/** One face found by [com.example.snapfindai.facecore.api.FaceDetector.detect], in the source image's pixel space. */
data class DetectedFace(
    val box: RectF,
    val score: Float,
    val landmarks: FaceLandmarks,
)
