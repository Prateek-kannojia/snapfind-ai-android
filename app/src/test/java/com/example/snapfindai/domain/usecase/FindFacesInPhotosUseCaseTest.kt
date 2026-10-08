package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.EventPhoto
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobCheckpoint
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.PhotoMatch
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
 * Covers the two properties of this use case that reading it cannot settle.
 *
 * **Resumption**: whether a second attempt continues the first or quietly
 * starts over. Both produce the same results, so only the work actually done
 * tells them apart -- hence a matcher that records which photos it was handed.
 *
 * A real process kill can't be simulated here, because it is precisely the
 * absence of unwinding: any exception still runs the `finally`, which cleans
 * up exactly what a kill leaves behind. So the two halves are tested
 * separately -- that an attempt writes a checkpoint as it goes, and that an
 * attempt handed a checkpoint continues from it -- through the same repository
 * API in both directions.
 *
 * **That nothing is unpacked**: photos are read out of the archive as they are
 * scored, and only the ones that match are ever written. Again invisible in
 * the results, so the tests look at what lands on disk.
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
            "photos are scored in entry-name order, not archive order -- a cursor into an order that varies means nothing",
            listOf("a.jpg", "b.jpg", "c.jpg"),
            matcher.scored,
        )
        assertEquals(
            "every photo advances the cursor, and the one that matched carries its match",
            listOf(1 to null, 2 to "b.jpg", 3 to null),
            history.checkpoints,
        )
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
        val matchesDir = File(workDir, "matches_$jobId").apply { mkdirs() }
        // What the killed attempt left behind: two photos scored, one of them
        // a match, already written out.
        val keptB = File(matchesDir, "b.jpg").apply { writeText("b.jpg") }
        history.recordScored(jobId, 1, null)
        history.recordScored(jobId, 2, FaceMatchResult(keptB, distance = 0.3f))
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
     * The point of the whole change: a 500MB archive used to be written out a
     * second time before anything was scored. Only matches reach the disk now,
     * and the rest are read straight out of the archive.
     */
    @Test
    fun `only the photos that match are ever written`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "a.jpg", "b.jpg", "c.jpg")

        val history = FakeHistory(temporaryFolder.newFolder("saved"))
        val result = FindFacesInPhotosUseCase(RecordingMatcher(matching = setOf("b.jpg")), history)(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        val written = result.getOrThrow().single()
        assertEquals("b.jpg", written.photo.name)
        assertEquals("the matched photo keeps its original bytes, not the decoded bitmap", "b.jpg", written.photo.readText())
        assertEquals(
            "the two photos that didn't match were never written anywhere",
            listOf("b.jpg"),
            history.inWorkDirAtCompletion,
        )
    }

    /**
     * An archive organised into folders flattens into one directory here. Two
     * photos with the same filename in different folders are still two
     * different photos, and the second must not land on top of the first.
     */
    @Test
    fun `two photos with the same name in different folders both survive`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "day1/IMG_001.jpg", "day2/IMG_001.jpg")

        val result = FindFacesInPhotosUseCase(
            RecordingMatcher(matching = setOf("day1/IMG_001.jpg", "day2/IMG_001.jpg")),
            FakeHistory(temporaryFolder.newFolder("saved")),
        )(jobId = jobId, selfieFile = selfieIn(workDir), zipFile = zip)

        val written = result.getOrThrow()
        assertEquals(listOf("IMG_001.jpg", "IMG_001-2.jpg"), written.map { it.photo.name })
        assertEquals(
            "each kept its own bytes",
            listOf("day1/IMG_001.jpg", "day2/IMG_001.jpg"),
            written.map { it.photo.readText() },
        )
    }

    /**
     * An entry name comes from the archive, which came from wherever the user
     * got it. A name that walks up the tree must not place a file outside the
     * job's own directory.
     */
    @Test
    fun `an entry that tries to escape the job directory cannot`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "../../evil.jpg")

        val result = FindFacesInPhotosUseCase(RecordingMatcher(matching = setOf("../../evil.jpg")), FakeHistory())(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        val written = result.getOrThrow().single().photo
        assertEquals(File(workDir, "matches_$jobId"), written.parentFile)
        assertEquals("evil.jpg", written.name)
    }

    /**
     * Scoring finishes, then the matched photos are relocated. A kill in that
     * gap leaves a cursor pointing past the end of the list -- and the right
     * answer is still "nothing left to score", not an exception.
     */
    @Test
    fun `a cursor past the end of the list scores nothing`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "a.jpg", "b.jpg")
        val kept = File(workDir, "matches_$jobId").apply { mkdirs() }.resolve("b.jpg").apply { writeText("b.jpg") }
        val history = FakeHistory().apply {
            recordScored(jobId, 9, FaceMatchResult(kept, distance = 0.3f))
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

    @Test
    fun `an archive with no photos in it is reported, not scored`() = runTest {
        val workDir = temporaryFolder.newFolder("work")
        val zip = zipInto(workDir, "notes.txt", "readme.md")

        val result = FindFacesInPhotosUseCase(RecordingMatcher(), FakeHistory())(
            jobId = jobId,
            selfieFile = selfieIn(workDir),
            zipFile = zip,
        )

        assertTrue(result.isFailure)
    }

    private fun selfieIn(workDir: File): File =
        File(workDir, "selfie.jpg").apply { writeText("selfie") }

    /** Each entry's bytes are its own name, so a written file can be checked against the entry it came from. */
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

    /** Records what it was asked to score, which is the only way to tell a resume from a restart. */
    private class RecordingMatcher(private val matching: Set<String> = emptySet()) : FaceMatchRepository {
        val scored = mutableListOf<String>()

        private object Selfie : PreparedSelfie

        override suspend fun prepareSelfie(selfie: File): PreparedSelfie = Selfie

        override suspend fun matchPhotos(
            selfie: PreparedSelfie,
            eventPhotos: List<EventPhoto>,
            threshold: Float,
            onScored: suspend (scored: Int, match: PhotoMatch?) -> Unit,
        ) {
            eventPhotos.forEachIndexed { index, photo ->
                scored += photo.name
                onScored(index + 1, if (photo.name in matching) PhotoMatch(photo, distance = 0.3f) else null)
            }
        }
    }

    /**
     * Holds the checkpoint in memory through the same calls the real
     * repository implements, so the test seeds a "killed attempt" the only way
     * the use case itself could have written one.
     */
    private class FakeHistory(private val savedDir: File? = null) : JobHistoryRepository {
        private var scoredCount = 0
        private val pending = mutableListOf<FaceMatchResult>()

        /** Every (cursor, matched photo name) this was told about, in order. */
        val checkpoints = mutableListOf<Pair<Int, String?>>()

        /**
         * What was sitting in the job's working directory at the moment it
         * completed. Captured here because the use case deletes that
         * directory on its way out, which is exactly the right thing to do
         * and leaves nothing for a test to look at afterwards.
         */
        var inWorkDirAtCompletion: List<String> = emptyList()
            private set

        override suspend fun checkpointFor(jobId: Long) =
            JobCheckpoint(scoredCount, pending.filter { it.photo.exists() })

        override suspend fun recordScored(jobId: Long, scoredCount: Int, match: FaceMatchResult?) {
            this.scoredCount = scoredCount
            match?.let { pending += it }
            checkpoints += scoredCount to match?.photo?.name
        }

        override suspend fun needsWork(jobId: Long) = true
        override suspend fun abandonJob(jobId: Long) = Unit

        /**
         * Relocates like the real one does. The contract says the returned
         * matches point at their new home and the caller's originals may be
         * gone -- a fake that returned them unchanged would hand back paths
         * into a directory the use case is about to delete.
         */
        override suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>): List<FaceMatchResult> {
            inWorkDirAtCompletion = matches.firstOrNull()?.photo?.parentFile?.list()?.sorted().orEmpty()
            val destination = savedDir ?: return matches
            return matches.map { match ->
                val moved = File(destination, match.photo.name)
                match.photo.copyTo(moved, overwrite = true)
                match.copy(photo = moved)
            }
        }

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
