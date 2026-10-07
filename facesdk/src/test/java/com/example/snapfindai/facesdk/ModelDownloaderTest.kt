package com.example.snapfindai.facesdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
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

    /**
     * The pause/resume loop end to end, and the reason any of the Range
     * handling exists: stopping part-way has to keep what it had, and
     * continuing has to ask only for the rest. A resume that quietly
     * re-downloaded everything would still produce a correct file, so the
     * byte count the server served is the only thing that can tell the
     * difference -- which is why the stub counts it.
     */
    @Test
    fun `a stopped download keeps its bytes and resuming asks only for the rest`() = runBlocking {
        val content = ByteArray(2_000_000) { it.toByte() }
        StubHttpServer(content, chunkDelayMs = 30).use { server ->
            val destDir = File(tempDir(), "cache")
            val partial = File(destDir, "model.onnx.download")

            val stopped = launch(Dispatchers.IO) {
                ModelDownloader.getOrDownload(server.url, destDir, "model.onnx", sha256(content))
            }
            withTimeout(10_000) { while (partial.length() == 0L) delay(10) }
            stopped.cancelAndJoin()

            val carried = partial.length()
            assertTrue("a stopped download should leave its bytes behind", carried in 1 until content.size.toLong())

            val result = ModelDownloader.getOrDownload(server.url, destDir, "model.onnx", sha256(content))

            assertArrayEquals("a resumed download still has to produce the whole file", content, result.readBytes())
            assertEquals("bytes=$carried-", server.lastRangeHeader)
            assertEquals(
                "the resume should have fetched only the missing bytes",
                content.size.toLong() - carried,
                server.bytesServedFromAnOffset.toLong(),
            )
        }
    }

    /**
     * A server free to ignore the Range header, which means the bytes on
     * disk can't be assumed to be a prefix of what's arriving. Appending to
     * them would build a file that fails its checksum with nothing to point
     * at, so the only safe answer is to overwrite.
     */
    @Test
    fun `starts over when the server ignores the range`() = runBlocking {
        val content = "the real model bytes".toByteArray()
        StubHttpServer(content, honourRange = false).use { server ->
            val destDir = File(tempDir(), "cache").apply { mkdirs() }
            File(destDir, "model.onnx.download").writeBytes("bytes from somewhere else entirely".toByteArray())

            val result = ModelDownloader.getOrDownload(server.url, destDir, "model.onnx", sha256(content))

            assertArrayEquals(content, result.readBytes())
        }
    }

    @Test
    fun `discardPartial throws away what a stopped download kept`() = runBlocking {
        val destDir = tempDir()
        val partial = File(destDir, "model.onnx.download").apply { writeBytes(ByteArray(1024)) }

        ModelDownloader.discardPartial(destDir, "model.onnx")

        assertFalse("cancelling outright shouldn't leave the download in storage", partial.exists())
    }

    /**
     * Enough of an HTTP server to answer one GET, with or without honouring
     * a byte range, and to say afterwards what it was asked for and how much
     * it sent. A real HTTP server library would be a heavier dependency than
     * the thing under test.
     */
    private class StubHttpServer(
        private val content: ByteArray,
        private val honourRange: Boolean = true,
        private val chunkDelayMs: Long = 0,
    ) : Closeable {
        private val serverSocket = ServerSocket(0)

        val url: String get() = "http://127.0.0.1:${serverSocket.localPort}/model.onnx"

        @Volatile var lastRangeHeader: String? = null
            private set

        /**
          * Counted only for responses that started at an offset, which is
          * what isolates a resume's traffic from the abandoned connection it
          * follows -- that one keeps pushing a chunk or two into the socket
          * after the client has gone, and a single total would charge them to
          * the resume.
          */
        @Volatile var bytesServedFromAnOffset: Int = 0
            private set

        init {
            Thread {
                while (!serverSocket.isClosed) {
                    // Per-connection, so a client that hangs up mid-transfer
                    // (which is exactly what a cancelled download does) ends
                    // that response rather than the whole server.
                    try {
                        serverSocket.accept().use { serve(it) }
                    } catch (_: Exception) {
                        if (serverSocket.isClosed) return@Thread
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun serve(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            var range: String? = null
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                line = reader.readLine()
            }
            lastRangeHeader = range

            val requestedStart = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val start = if (honourRange) requestedStart.coerceIn(0, content.size) else 0
            val body = content.copyOfRange(start, content.size)

            val output = client.getOutputStream()
            output.write(
                buildString {
                    append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    if (start > 0) append("Content-Range: bytes $start-${content.size - 1}/${content.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
            )

            // Chunked with an optional pause between chunks, so a test can
            // reliably stop a download part-way instead of racing a transfer
            // that finishes in microseconds over loopback.
            var sent = 0
            while (sent < body.size) {
                val size = minOf(32 * 1024, body.size - sent)
                output.write(body, sent, size)
                output.flush()
                sent += size
                if (start > 0) bytesServedFromAnOffset += size
                if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
            }
        }

        override fun close() = serverSocket.close()
    }
}
