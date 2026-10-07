package com.example.snapfindai.utils

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
