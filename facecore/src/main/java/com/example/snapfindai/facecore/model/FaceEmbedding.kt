package com.example.snapfindai.facesdk.model

/**
 * A 512-D w600k_mbf face embedding, produced by
 * [com.example.snapfindai.facesdk.api.FaceEmbedder.embed]. Wrapped rather than
 * a bare FloatArray so callers can't accidentally pass a differently-shaped
 * vector (e.g. from a different model) into [com.example.snapfindai.facesdk.FaceMatcher].
 */
class FaceEmbedding(val values: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is FaceEmbedding && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()
}
