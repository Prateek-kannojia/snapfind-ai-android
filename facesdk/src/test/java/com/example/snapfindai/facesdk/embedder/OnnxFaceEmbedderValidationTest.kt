package com.example.snapfindai.facesdk.embedder

import com.example.snapfindai.facesdk.InvalidModelException
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure logic, no OrtSession needed -- see ScrfdFaceDetectorValidationTest for why. */
class OnnxFaceEmbedderValidationTest {

    private val validInputs = setOf(OnnxFaceEmbedder.INPUT_NAME)

    @Test
    fun `accepts a correctly-shaped model`() {
        OnnxFaceEmbedder.validateShape(validInputs, outputCount = 1, outputShape = OnnxFaceEmbedder.EXPECTED_EMBEDDING_SIZE) // does not throw
    }

    @Test
    fun `accepts a model whose output shape is dynamic (unreported, -1)`() {
        OnnxFaceEmbedder.validateShape(validInputs, outputCount = 1, outputShape = -1L) // does not throw
    }

    @Test
    fun `accepts a model whose shape info couldn't be determined at all`() {
        OnnxFaceEmbedder.validateShape(validInputs, outputCount = 1, outputShape = null) // does not throw
    }

    @Test
    fun `rejects a model missing the expected input name`() {
        val e = assertThrows(InvalidModelException::class.java) {
            OnnxFaceEmbedder.validateShape(setOf("some_other_input"), outputCount = 1, outputShape = 512L)
        }
        assert(e.message!!.contains("input.1"))
    }

    @Test
    fun `rejects a model with more than one output -- a detector loaded as an embedder, say`() {
        val e = assertThrows(InvalidModelException::class.java) {
            OnnxFaceEmbedder.validateShape(validInputs, outputCount = 9, outputShape = null)
        }
        assert(e.message!!.contains("1"))
    }

    @Test
    fun `rejects a known-wrong embedding size`() {
        val e = assertThrows(InvalidModelException::class.java) {
            OnnxFaceEmbedder.validateShape(validInputs, outputCount = 1, outputShape = 256L)
        }
        assert(e.message!!.contains("512"))
    }
}
