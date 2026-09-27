package com.example.snapfindai.facesdk

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.example.snapfindai.facesdk.api.FaceDetector
import com.example.snapfindai.facesdk.api.FaceEmbedder
import com.example.snapfindai.facesdk.model.DetectedFace
import com.example.snapfindai.facesdk.model.EventPhotoInput
import com.example.snapfindai.facesdk.model.FaceEmbedding
import com.example.snapfindai.facesdk.model.FaceLandmarks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The facade's matching logic (selfie-must-have-a-face, per-photo failure
 * isolation, the logging hook) tested against fake FaceDetector/FaceEmbedder
 * -- no real ONNX model needed, since that's exactly what those interfaces
 * exist to let us substitute.
 */
@RunWith(RobolectricTestRunner::class)
class FaceMatchEngineTest {

    // A well-posed (non-degenerate) 5-point landmark set -- reused for every
    // fake detection since FaceAligner's math requires real variance between
    // points, and what the points actually are doesn't matter here: the
    // fakes control the resulting embedding directly, bypassing real pixel
    // content entirely.
    private val someLandmarks = FaceLandmarks(
        arrayOf(
            PointF(38.2946f, 51.6963f), PointF(73.5318f, 51.5014f), PointF(56.0252f, 71.7366f),
            PointF(41.5493f, 92.3655f), PointF(70.7299f, 92.2041f),
        )
    )
    private val someBox = RectF(0f, 0f, 100f, 100f)

    private fun bitmap(size: Int = 4) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private class RecordingLogger : FaceSdkLogger {
        val errors = mutableListOf<Pair<String, Throwable>>()
        override fun onError(message: String, throwable: Throwable) {
            errors += message to throwable
        }
    }

    private class FakeFaceDetector(
        private val facesFor: Map<Bitmap, () -> List<DetectedFace>>,
    ) : FaceDetector {
        override suspend fun detect(bitmap: Bitmap): List<DetectedFace> =
            (facesFor[bitmap] ?: error("no fake configured for this bitmap"))()
        override fun close() {}
    }

    private class FakeFaceEmbedder(private val embeddingFor: (Bitmap) -> FaceEmbedding) : FaceEmbedder {
        override suspend fun embed(alignedFace: Bitmap): FaceEmbedding = embeddingFor(alignedFace)
        override fun close() {}
    }

    @Test
    fun `embedSelfie throws NoFaceDetectedException when no face is found`() = runBlocking {
        val selfie = bitmap()
        val detector = FakeFaceDetector(mapOf(selfie to { emptyList() }))
        val embedder = FakeFaceEmbedder { FaceEmbedding(floatArrayOf(1f, 0f)) }
        val engine = FaceMatchEngine(detector, embedder, FaceSdkLogger.NONE)

        try {
            engine.embedSelfie(selfie)
            fail("expected NoFaceDetectedException")
        } catch (e: NoFaceDetectedException) {
            // expected
        }
    }

    @Test
    fun `scoreEventPhoto returns null with no logged error when the photo has no face`() = runBlocking {
        val selfie = bitmap()
        val noFacePhoto = bitmap()
        val detector = FakeFaceDetector(
            mapOf(
                selfie to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) },
                noFacePhoto to { emptyList() },
            )
        )
        val embedder = FakeFaceEmbedder { FaceEmbedding(floatArrayOf(1f, 0f)) }
        val logger = RecordingLogger()
        val engine = FaceMatchEngine(detector, embedder, logger)

        val selfieEmbedding = engine.embedSelfie(selfie)
        val distance = engine.scoreEventPhoto(selfieEmbedding, noFacePhoto)

        assertNull(distance)
        assertTrue("a missing face is a valid outcome, not an error", logger.errors.isEmpty())
    }

    @Test
    fun `matchJob isolates a per-photo failure -- logs it, scores it as no match, keeps scoring the rest`() = runBlocking {
        val selfie = bitmap()
        val goodPhoto = bitmap()
        val badPhoto = bitmap()

        val detector = FakeFaceDetector(
            mapOf(
                selfie to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) },
                goodPhoto to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) },
                badPhoto to { throw IllegalStateException("corrupt decode") },
            )
        )
        val embedder = FakeFaceEmbedder { FaceEmbedding(floatArrayOf(1f, 0f)) } // identical to selfie -> distance 0
        val logger = RecordingLogger()
        val engine = FaceMatchEngine(detector, embedder, logger)

        val results = engine.matchJob(
            selfie,
            listOf(EventPhotoInput("good.jpg", goodPhoto), EventPhotoInput("bad.jpg", badPhoto)),
        )

        val good = results.single { it.id == "good.jpg" }
        val bad = results.single { it.id == "bad.jpg" }

        assertEquals(0f, good.distance!!, 1e-6f)
        assertTrue(good.isMatch)

        assertNull(bad.distance)
        assertFalse(bad.isMatch)
        assertEquals(1, logger.errors.size)
        assertTrue(logger.errors.single().first.contains("bad.jpg"))
        assertEquals("corrupt decode", logger.errors.single().second.message)
    }

    @Test
    fun `matchJob respects a custom threshold`() = runBlocking {
        val selfie = bitmap()
        val farPhoto = bitmap()
        val detector = FakeFaceDetector(
            mapOf(
                selfie to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) },
                farPhoto to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) },
            )
        )
        // embed() always receives a freshly-aligned crop, never the original
        // selfie/farPhoto bitmap instance, so fakes can't key off identity --
        // key off call order instead. Each matchJob() call below does exactly
        // one selfie embed followed by one event-photo embed, so odd calls
        // are always the selfie and even calls are always farPhoto.
        var calls = 0
        val embedder = FakeFaceEmbedder {
            calls++
            if (calls % 2 == 1) FaceEmbedding(floatArrayOf(1f, 0f)) else FaceEmbedding(floatArrayOf(0f, 1f))
        } // selfie embeds to [1,0]; farPhoto embeds to [0,1] -> cosine distance 1.0
        val engine = FaceMatchEngine(detector, embedder, FaceSdkLogger.NONE)

        val strict = engine.matchJob(selfie, listOf(EventPhotoInput("far.jpg", farPhoto)), threshold = 0.6f)
        val lenient = engine.matchJob(selfie, listOf(EventPhotoInput("far.jpg", farPhoto)), threshold = 1.0f)

        assertFalse(strict.single().isMatch)
        assertTrue(lenient.single().isMatch)
    }

    @Test
    fun `using the engine after close throws instead of silently misbehaving`() = runBlocking {
        val selfie = bitmap()
        val detector = FakeFaceDetector(mapOf(selfie to { listOf(DetectedFace(someBox, 0.9f, someLandmarks)) }))
        val embedder = FakeFaceEmbedder { FaceEmbedding(floatArrayOf(1f, 0f)) }
        val engine = FaceMatchEngine(detector, embedder, FaceSdkLogger.NONE)

        engine.close()

        try {
            engine.embedSelfie(selfie)
            fail("expected an IllegalStateException after close()")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }
}
