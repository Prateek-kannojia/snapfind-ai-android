package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobCheckpoint
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.FaceMatchRepository
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.repository.PreparedSelfie
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers resumption, which is the one property of this use case that cannot
 * be checked by reading it: whether a second attempt at the same job continues
 * the first or quietly starts over. Both produce correct results, so only the
 * work actually done distinguishes them -- hence a matcher that records which
 * photos it was handed.
 *
 * A real process kill can't be simulated here, because it is precisely the
 * absence of unwinding: any exception still runs the `finally`, which cleans
 * up exactly what a kill leaves behind. So the two halves are tested
 * separately -- that an attempt writes a checkpoint as it goes, and that an
 * attempt handed a checkpoint continues from it -- using the same repository
 * API in both directions.
 */
class FindFacesInPhotosUseCaseTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val jobId = 7L

    @Test
    fun `the first attempt records its progress as it goes`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "b.jpg", "a.jpg", "c.jpg")
        val history = FakeHistory()
        val matcher = RecordingMatcher(matching = setOf("b.jpg"))

        val result = FindFacesInPhotosUseCase(matcher, history)(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "a photo is scored in path order, not archive order -- a cursor into an order that varies means nothing",
            listOf("a.jpg", "b.jpg", "c.jpg"),
            matcher.scored,
        )
        assertEquals(
            "every photo advances the cursor, and the one that matched carries its match",
            listOf(1 to null, 2 to "b.jpg", 3 to null),
            history.checkpoints,
        )
        assertTrue("extraction has to be recorded complete before anything is scored", history.extractionRecordedFirst)
    }

    /**
     * The headline claim. Without this the attempt re-scores everything, which
     * on a device that kills before any attempt finishes means the job never
     * terminates at all.
     */
    @Test
    fun `a second attempt scores only the photos the first did not`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "a.jpg", "b.jpg", "c.jpg", "d.jpg")
        val history = FakeHistory()
        val extractDir = File(workDir, "event_photos_$jobId")
        // What the killed attempt left behind: everything extracted, two
        // photos scored, one of them a match.
        extractPhotos(extractDir, "a.jpg", "b.jpg", "c.jpg", "d.jpg")
        history.markExtractionComplete(jobId)
        history.recordScored(jobId, 1, null)
        history.recordScored(jobId, 2, FaceMatchResult(File(extractDir, "b.jpg"), distance = 0.3f))
        // The seeding above went through the same calls the killed attempt
        // would have made, so the log is reset to leave only what this
        // attempt does.
        history.checkpoints.clear()

        val matcher = RecordingMatcher(matching = setOf("d.jpg"))
        val result = FindFacesInPhotosUseCase(matcher, history)(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        assertEquals("the first two were already done", listOf("c.jpg", "d.jpg"), matcher.scored)
        assertEquals(
            "a resumed job's results are the old matches plus the new ones",
            listOf("b.jpg", "d.jpg"),
            result.getOrThrow().map { it.photo.name },
        )
        assertEquals(
            "the cursor keeps counting from where it was, not from this attempt's zero",
            listOf(3 to null, 4 to "d.jpg"),
            history.checkpoints,
        )
    }

    /**
     * Extraction is minutes of work on a real event folder, and re-doing it
     * is what the flag exists to prevent. Proven by making the archive
     * unreadable: if anything touched it, this would fail instead of finishing
     * from what is already on disk.
     */
    @Test
    fun `a completed extraction is not repeated`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = File(workDir, "events.zip").apply { writeText("not a zip at all") }
        val extractDir = File(workDir, "event_photos_$jobId")
        extractPhotos(extractDir, "a.jpg")
        val history = FakeHistory().apply { markExtractionComplete(jobId) }

        val matcher = RecordingMatcher(matching = setOf("a.jpg"))
        val result = FindFacesInPhotosUseCase(matcher, history)(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("a.jpg"), matcher.scored)
    }

    /**
     * Scoring finishes, then the non-matching photos are deleted to make room
     * for the copy that follows. A kill in that gap leaves a cursor pointing
     * past the end of a list that is now shorter than it was -- and the right
     * answer is still "nothing left to score", not an exception and not a
     * re-run.
     */
    @Test
    fun `a cursor past the end of a pruned list scores nothing`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "a.jpg", "b.jpg", "c.jpg")
        val extractDir = File(workDir, "event_photos_$jobId")
        // Only the match survived the pruning the killed attempt had started.
        extractPhotos(extractDir, "b.jpg")
        val history = FakeHistory().apply {
            markExtractionComplete(jobId)
            recordScored(jobId, 3, FaceMatchResult(File(extractDir, "b.jpg"), distance = 0.3f))
        }

        val matcher = RecordingMatcher()
        val result = FindFacesInPhotosUseCase(matcher, history)(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        assertEquals(emptyList<String>(), matcher.scored)
        assertEquals(listOf("b.jpg"), result.getOrThrow().map { it.photo.name })
    }

    private fun selfieIn(workDir: File): File =
        File(workDir, "selfie.jpg").apply { writeText("selfie") }

    private fun zipInto(workDir: File, vararg names: String): File {
        val zip = File(workDir, "events.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            names.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(name.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    private fun extractPhotos(extractDir: File, vararg names: String) {
        extractDir.mkdirs()
        names.forEach { File(extractDir, it).writeText(it) }
    }

    /** Records what it was asked to score, which is the only way to tell a resume from a restart. */
    private class RecordingMatcher(private val matching: Set<String> = emptySet()) : FaceMatchRepository {
        val scored = mutableListOf<String>()

        private object Selfie : PreparedSelfie

        override suspend fun prepareSelfie(selfie: File): PreparedSelfie = Selfie

        override suspend fun matchPhotos(
            selfie: PreparedSelfie,
            eventPhotos: List<File>,
            threshold: Float,
            onScored: (suspend (scored: Int, match: FaceMatchResult?) -> Unit)?,
        ): List<FaceMatchResult> {
            val matches = mutableListOf<FaceMatchResult>()
            eventPhotos.forEachIndexed { index, photo ->
                scored += photo.name
                val match = if (photo.name in matching) {
                    FaceMatchResult(photo = photo, distance = 0.3f).also { matches += it }
                } else {
                    null
                }
                onScored?.invoke(index + 1, match)
            }
            return matches
        }
    }

    /**
     * Holds the checkpoint in memory through the same calls the real
     * repository implements, so the test seeds a "killed attempt" the only way
     * the use case itself could have written one.
     */
    private class FakeHistory : JobHistoryRepository {
        private var scoredCount = 0
        private var extractionComplete = false
        private val pending = mutableListOf<FaceMatchResult>()

        /** Every (cursor, matched photo name) this was told about, in order. */
        val checkpoints = mutableListOf<Pair<Int, String?>>()
        var extractionRecordedFirst = false
            private set

        override suspend fun checkpointFor(jobId: Long) =
            JobCheckpoint(scoredCount, extractionComplete, pending.filter { it.photo.exists() })

        override suspend fun markExtractionComplete(jobId: Long) {
            extractionComplete = true
            extractionRecordedFirst = checkpoints.isEmpty()
        }

        override suspend fun recordScored(jobId: Long, scoredCount: Int, match: FaceMatchResult?) {
            this.scoredCount = scoredCount
            match?.let { pending += it }
            checkpoints += scoredCount to match?.photo?.name
        }

        override suspend fun needsWork(jobId: Long) = true
        override suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>) = matches
        override suspend fun abandonJob(jobId: Long) = Unit

        override suspend fun findOrStartJob(workId: String, threshold: Float): Long = unused()
        override suspend fun deleteJob(jobId: Long) = unused()
        override suspend fun abandonedJobs(): List<AbandonedJob> = unused()
        override suspend fun getLastJob(): SavedJob? = unused()
        override suspend fun getJob(jobId: Long): SavedJob? = unused()
        override suspend fun getAllJobSummaries(): List<JobSummary> = unused()
        override suspend fun markSavedToGallery(photo: File, savedAt: Long) = unused()
        override suspend fun clearSavedToGallery(photos: List<File>) = unused()
        override suspend fun removeMatches(photos: List<File>) = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not part of running a job")
    }
}
