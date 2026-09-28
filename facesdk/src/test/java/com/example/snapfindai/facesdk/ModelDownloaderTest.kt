package com.example.snapfindai.facesdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Plain JVM test -- no Robolectric, no real network. java.net.URL treats a
 * file:// URL the same way it treats http(s)://, so a temp file stands in
 * for "a server somewhere" without a mock HTTP server.
 */
class ModelDownloaderTest {

    private fun tempDir(): File = Files.createTempDirectory("model-downloader-test").toFile()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sourceFile(dir: File, content: ByteArray): File =
        File(dir, "source.bin").apply { writeBytes(content) }

    @Test
    fun `downloads and caches a file matching the expected checksum`() = runBlocking {
        val dir = tempDir()
        val content = "hello model weights".toByteArray()
        val source = sourceFile(dir, content)
        val destDir = File(dir, "cache")

        val result = ModelDownloader.getOrDownload(
            url = source.toURI().toString(), destDir = destDir, fileName = "model.onnx", sha256 = sha256(content),
        )

        assertTrue(result.exists())
        assertArrayEquals(content, result.readBytes())
    }

    @Test
    fun `does not re-download when a valid cached copy already exists`() = runBlocking {
        val dir = tempDir()
        val content = "hello model weights".toByteArray()
        val source = sourceFile(dir, content)
        val destDir = File(dir, "cache")
        val checksum = sha256(content)

        val first = ModelDownloader.getOrDownload(source.toURI().toString(), destDir, "model.onnx", checksum)
        source.delete() // if the second call tries to re-download, it'll fail -- the source is gone

        val second = ModelDownloader.getOrDownload(source.toURI().toString(), destDir, "model.onnx", checksum)

        assertEquals(first.absolutePath, second.absolutePath)
        assertArrayEquals(content, second.readBytes())
    }

    @Test
    fun `re-downloads when the cached file exists but doesn't match the checksum -- simulates a corrupted prior download`() = runBlocking {
        val dir = tempDir()
        val goodContent = "the real model bytes".toByteArray()
        val source = sourceFile(dir, goodContent)
        val destDir = File(dir, "cache").apply { mkdirs() }
        File(destDir, "model.onnx").writeBytes("corrupted leftover from a crashed download".toByteArray())

        val result = ModelDownloader.getOrDownload(source.toURI().toString(), destDir, "model.onnx", sha256(goodContent))

        assertArrayEquals(goodContent, result.readBytes())
    }

    @Test
    fun `throws and does not cache anything when the download doesn't match the expected checksum`() = runBlocking {
        val dir = tempDir()
        val content = "hello model weights".toByteArray()
        val source = sourceFile(dir, content)
        val destDir = File(dir, "cache")
        val wrongChecksum = sha256("not the right content".toByteArray())

        try {
            ModelDownloader.getOrDownload(source.toURI().toString(), destDir, "model.onnx", wrongChecksum)
            fail("expected ChecksumMismatchException")
        } catch (e: ModelDownloader.ChecksumMismatchException) {
            // expected
        }

        assertFalse("a checksum-mismatched download must not be left behind as if it were cached", File(destDir, "model.onnx").exists())
    }

    @Test
    fun `reports download progress`() = runBlocking {
        val dir = tempDir()
        val content = ByteArray(200_000) { it.toByte() } // large enough to span multiple 64KB read chunks
        val source = sourceFile(dir, content)
        val destDir = File(dir, "cache")

        val progressCalls = mutableListOf<Pair<Long, Long>>()
        ModelDownloader.getOrDownload(
            source.toURI().toString(), destDir, "model.onnx", sha256(content),
            onProgress = { downloaded, total -> progressCalls += downloaded to total },
        )

        assertTrue("expected multiple progress callbacks for a 200KB file in 64KB chunks", progressCalls.size > 1)
        assertEquals(content.size.toLong(), progressCalls.last().first)
    }
}
