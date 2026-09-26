package com.example.snapfindai.facesdk

/** Thrown by [FaceMatchEngine.embedSelfie] when the selfie has no detectable face — a genuine job-level failure, unlike an event photo with no face (which just scores as no match). */
class NoFaceDetectedException(message: String) : Exception(message)
