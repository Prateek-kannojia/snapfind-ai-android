package com.example.snapfindai.facesdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pure Kotlin, no Android framework involved — plain JVM test, no Robolectric needed. */
class FaceEmbeddingTest {

    @Test
    fun `equal content means equal, even as different array instances`() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        val b = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different content means not equal`() {
        val a = FaceEmbedding(floatArrayOf(1f, 2f, 3f))
        val b = FaceEmbedding(floatArrayOf(1f, 2f, 4f))
        assertNotEquals(a, b)
    }
}
