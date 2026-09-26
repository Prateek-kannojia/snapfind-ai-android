package com.example.snapfindai.facesdk

import com.example.snapfindai.facesdk.model.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure math, no Android framework involved — plain JVM test, no Robolectric needed. */
class FaceMatcherTest {

    @Test
    fun `identical embeddings have zero distance`() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(0f, FaceMatcher.distance(a, a), 1e-6f)
    }

    @Test
    fun `opposite embeddings have distance 2`() {
        val a = FaceEmbedding(floatArrayOf(1f, 0f))
        val b = FaceEmbedding(floatArrayOf(-1f, 0f))
        assertEquals(2f, FaceMatcher.distance(a, b), 1e-6f)
    }

    @Test
    fun `orthogonal embeddings have distance 1`() {
        val a = FaceEmbedding(floatArrayOf(1f, 0f))
        val b = FaceEmbedding(floatArrayOf(0f, 1f))
        assertEquals(1f, FaceMatcher.distance(a, b), 1e-6f)
    }

    @Test
    fun `distance is symmetric`() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f, -3f))
        val b = FaceEmbedding(floatArrayOf(4f, -1f, 2f))
        assertEquals(FaceMatcher.distance(a, b), FaceMatcher.distance(b, a), 1e-6f)
    }

    @Test
    fun `a zero-vector embedding never matches -- undefined cosine treated as max distance`() {
        val a = FaceEmbedding(floatArrayOf(0f, 0f, 0f))
        val b = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, FaceMatcher.distance(a, b), 1e-6f)
    }

    @Test
    fun `isMatch is inclusive at the threshold boundary`() {
        assertTrue(FaceMatcher.isMatch(distance = 0.60f, threshold = 0.60f))
        assertTrue(FaceMatcher.isMatch(distance = 0.59f, threshold = 0.60f))
        assertFalse(FaceMatcher.isMatch(distance = 0.61f, threshold = 0.60f))
    }

    @Test
    fun `isMatch defaults to DEFAULT_THRESHOLD`() {
        assertTrue(FaceMatcher.isMatch(FaceMatcher.DEFAULT_THRESHOLD))
        assertFalse(FaceMatcher.isMatch(FaceMatcher.DEFAULT_THRESHOLD + 0.01f))
    }
}
