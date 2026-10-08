package com.example.snapfindai.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression cover for the free-space precheck.
 *
 * It originally measured the directory it was about to extract *into*, which
 * does not exist yet at that point -- and `usableSpace` answers 0 for a path
 * naming no partition. So it reported no free space on every device and
 * rejected every job, with a message quoting a plausible-looking figure.
 *
 * Worth a test precisely because the failure was not an exception: the API
 * returns a number that reads like an answer.
 */
class FileHelperTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `a small archive on a disk with space reports room`() {
        val zip = temporaryFolder.newFile("events.zip").apply { writeBytes(ByteArray(1024)) }

        assertTrue(
            "a 1KB archive must not be rejected on a disk that plainly has room",
            FileHelper.hasRoomToExtract(zip),
        )
    }

    @Test
    fun `the requirement exceeds the archive's own size`() {
        val zip = temporaryFolder.newFile("events.zip").apply { writeBytes(ByteArray(1_000_000)) }

        assertTrue(
            "the margin has to cover relocating matched photos after extraction",
            FileHelper.requiredSpaceToExtract(zip) > zip.length(),
        )
    }

    /**
     * Extraction has to be resumable for a killed job to be worth resuming:
     * re-running it re-wrote every file, which on a real event folder is the
     * minutes the resume was supposed to save.
     *
     * An untouched modification time is the evidence -- the returned list
     * looks identical whether or not the file was rewritten.
     */
    @Test
    fun `extracting again leaves already-extracted files alone`() = runBlocking {
        val zip = zipOf("a.jpg" to "first", "day2/b.jpg" to "second")
        val destDir = temporaryFolder.newFolder("extract")

        val first = FileHelper.unzip(zip, destDir, maxTotalBytes = 1_000_000)
        val untouchedMarker = 1_000_000_000L
        first.forEach { it.setLastModified(untouchedMarker) }

        val second = FileHelper.unzip(zip, destDir, maxTotalBytes = 1_000_000)

        assertEquals("the same set of files either way", first.map { it.path }.sorted(), second.map { it.path }.sorted())
        assertTrue(
            "a file already extracted must not be written again",
            second.all { it.lastModified() == untouchedMarker },
        )
        assertEquals("second", File(destDir, "day2/b.jpg").readText())
    }

    /**
     * What makes skipping safe. Writing straight to the final name would
     * leave a truncated file after a kill that looks exactly like a finished
     * one, so the next attempt would skip it and hand a half-written photo to
     * the decoder.
     */
    @Test
    fun `a half-written entry is not mistaken for an extracted one`() = runBlocking {
        val zip = zipOf("a.jpg" to "the whole entry")
        val destDir = temporaryFolder.newFolder("extract")
        // What a process killed mid-entry leaves behind.
        File(destDir, "a.jpg.part").writeText("the wh")

        val extracted = FileHelper.unzip(zip, destDir, maxTotalBytes = 1_000_000)

        assertEquals(listOf(File(destDir, "a.jpg")), extracted)
        assertEquals("the whole entry", File(destDir, "a.jpg").readText())
    }

    /**
     * The expansion guard counts bytes as they are written, so a resumed
     * extraction -- which writes almost nothing -- could otherwise walk past
     * a limit that stopped the first attempt.
     */
    @Test
    fun `the expansion guard still counts files a previous attempt extracted`() = runBlocking {
        val zip = zipOf("a.jpg" to "x".repeat(500), "b.jpg" to "y".repeat(500))
        val destDir = temporaryFolder.newFolder("extract")
        runCatching { FileHelper.unzip(zip, destDir, maxTotalBytes = 1_000_000) }

        try {
            FileHelper.unzip(zip, destDir, maxTotalBytes = 600)
            fail("the guard should have counted the already-extracted bytes")
        } catch (expected: IOException) {
            // expected
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = temporaryFolder.newFile("events-${entries.hashCode()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }
}
