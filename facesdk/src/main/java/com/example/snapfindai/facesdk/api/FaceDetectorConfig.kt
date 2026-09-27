package com.example.snapfindai.facesdk.api

/**
 * Tuning knobs for the SCRFD detector. Defaults match insightface's own
 * SCRFD defaults, and what production has always run with — pass a custom
 * config only when you actually need different behavior (e.g. a lower
 * [detThreshold] to catch more low-confidence faces at the cost of more
 * false positives).
 */
data class FaceDetectorConfig(
    /** Minimum confidence score [0, 1] for a candidate box to be kept at all. */
    val detThreshold: Float = 0.5f,
    /** IoU threshold above which a lower-scoring overlapping box is suppressed as a duplicate. */
    val nmsThreshold: Float = 0.4f,
    /** The square size (pixels) the input image is letterboxed into before inference. Must match what the loaded model was exported/traced for. */
    val inputSize: Int = 800,
)
