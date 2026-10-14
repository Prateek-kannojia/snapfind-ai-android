package com.example.snapfindai.facesdk.detector

import com.example.snapfindai.facesdk.InvalidModelException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure logic, no OrtSession needed -- ONNX Runtime's native library is
 * Android-only and can't load inside this desktop JVM test, so the shape
 * check is tested directly against plain names/counts instead.
 */
class ScrfdFaceDetectorValidationTest {

    private val validInputs = setOf(ScrfdFaceDetector.INPUT_NAME)
    private val validOutputs = (0 until ScrfdFaceDetector.EXPECTED_OUTPUT_COUNT).map { "out$it" }.toSet()

    @Test
    fun `accepts a correctly-shaped model`() {
        ScrfdFaceDetector.validateShape(validInputs, validOutputs) // does not throw
    }

    @Test
    fun `rejects a model missing the expected input name`() {
        val e = assertThrows(InvalidModelException::class.java) {
            ScrfdFaceDetector.validateShape(setOf("some_other_input"), validOutputs)
        }
        assert(e.message!!.contains("input.1"))
    }

    @Test
    fun `rejects a model with the wrong output count -- an embedder loaded as a detector, say`() {
        val embedderShapedOutputs = setOf("embedding") // 1 output, not 9
        val e = assertThrows(InvalidModelException::class.java) {
            ScrfdFaceDetector.validateShape(validInputs, embedderShapedOutputs)
        }
        assert(e.message!!.contains("9"))
    }
}
