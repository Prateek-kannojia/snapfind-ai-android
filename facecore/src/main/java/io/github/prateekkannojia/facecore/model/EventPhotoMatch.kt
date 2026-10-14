package io.github.prateekkannojia.facecore.model

import android.graphics.Bitmap

/** One event photo to score against a selfie in [io.github.prateekkannojia.facecore.FaceMatchEngine.matchJob]. */
data class EventPhotoInput(val id: String, val bitmap: Bitmap)

/**
 * The result of scoring one event photo against a selfie embedding.
 * [distance] is null when no face was found in the photo — a valid
 * outcome, not an error, matching the job,file,d contract the backend
 * and benchmark scripts already score against ([distance] blank = no match).
 */
data class EventPhotoMatchResult(
    val id: String,
    val distance: Float?,
    val isMatch: Boolean,
)
