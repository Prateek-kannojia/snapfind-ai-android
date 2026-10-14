package io.github.prateekkannojia.facecore.model

/**
 * A 512-D w600k_mbf face embedding, produced by
 * [io.github.prateekkannojia.facecore.api.FaceEmbedder.embed]. Wrapped rather than
 * a bare FloatArray so callers can't accidentally pass a differently-shaped
 * vector (e.g. from a different model) into [io.github.prateekkannojia.facecore.FaceMatcher].
 */
class FaceEmbedding(val values: FloatArray) {
    override fun equals(other: Any?): Boolean =
        other is FaceEmbedding && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()
}
