package com.example.snapfindai.facesdk

import com.example.snapfindai.facesdk.model.FaceEmbedding
import kotlin.math.sqrt

/**
 * Cosine distance between two embeddings, and the match/no-match decision
 * on top of it. [DEFAULT_THRESHOLD] is the value chosen in
 * Face_recognition/benchmarks/RESULTS.md's embedder-comparison sweep for
 * w600k_mbf on a 163-photo labelled corpus (positives and negatives):
 * precision 1.000, recall 0.842, zero false positives.
 */
object FaceMatcher {
    const val DEFAULT_THRESHOLD = 0.60f

    fun distance(a: FaceEmbedding, b: FaceEmbedding): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.values.indices) {
            dot += a.values[i] * b.values[i]
            na += a.values[i] * a.values[i]
            nb += b.values[i] * b.values[i]
        }
        if (na == 0f || nb == 0f) return 1f
        return 1f - dot / (sqrt(na) * sqrt(nb))
    }

    fun isMatch(distance: Float, threshold: Float = DEFAULT_THRESHOLD): Boolean = distance <= threshold
}
