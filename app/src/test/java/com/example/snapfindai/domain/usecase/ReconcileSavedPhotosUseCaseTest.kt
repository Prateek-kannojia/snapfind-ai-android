package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.GallerySaveResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The saved tick claims "this photo is in your gallery right now", which is
 * only true if it tracks deletions made outside the app. These cover the
 * three outcomes that claim depends on, including the one where the honest
 * answer is to change nothing.
 *
 * Plain JUnit with hand-written fakes rather than a mocking framework: the
 * interesting behaviour is in how the use case reacts to what the gallery
 * says, so the fakes are the test's subject matter, not boilerplate to hide.
 */
class ReconcileSavedPhotosUseCaseTest {

    private fun match(name: String, savedAt: Long?, galleryName: String) = FaceMatchResult(
        photo = File("/data/saved_matches/1/$name"),
        distance = 0.4f,
        savedAt = savedAt,
        galleryName = galleryName,
    )

    @Test
    fun `clears the tick when the photo is no longer in the gallery`() = runTest {
        val history = RecordingJobHistory()
        val reconcile = ReconcileSavedPhotosUseCase(
            photoGalleryRepository = FakeGallery(present = emptySet()),
            jobHistoryRepository = history,
        )

        val result = reconcile(listOf(match("a.jpg", savedAt = 123L, galleryName = "gallery_a.jpg")))

        assertNull("tick should be cleared once the file is gone", result.single().savedAt)
        assertEquals(
            "the cleared flag has to be persisted, not just returned",
            listOf(File("/data/saved_matches/1/a.jpg")),
            history.cleared,
        )
    }

    @Test
    fun `restores the tick when the photo is present but unflagged`() = runTest {
        val history = RecordingJobHistory()
        val reconcile = ReconcileSavedPhotosUseCase(
            photoGalleryRepository = FakeGallery(present = setOf("gallery_a.jpg")),
            jobHistoryRepository = history,
        )

        val result = reconcile(listOf(match("a.jpg", savedAt = null, galleryName = "gallery_a.jpg")))

        assertNotNull("a photo already in the gallery should read as saved", result.single().savedAt)
        assertEquals(listOf(File("/data/saved_matches/1/a.jpg")), history.marked)
    }

    /**
     * The case most likely to be got wrong, and the most destructive if it
     * is: null from the gallery means "couldn't read it", not "it's empty".
     * Treating the two the same would clear every tick in the app the first
     * time a permission was missing.
     */
    @Test
    fun `changes nothing when the gallery cannot be read`() = runTest {
        val history = RecordingJobHistory()
        val reconcile = ReconcileSavedPhotosUseCase(
            photoGalleryRepository = FakeGallery(present = null),
            jobHistoryRepository = history,
        )
        val saved = match("a.jpg", savedAt = 123L, galleryName = "gallery_a.jpg")

        val result = reconcile(listOf(saved))

        assertEquals("the input should come back untouched", listOf(saved), result)
        assertTrue("nothing should have been written", history.cleared.isEmpty() && history.marked.isEmpty())
    }

    private class FakeGallery(private val present: Set<String>?) : PhotoGalleryRepository {
        override suspend fun savedDisplayNames(): Set<String>? = present
        override suspend fun saveToGallery(photo: File, displayName: String): GallerySaveResult =
            throw UnsupportedOperationException("not part of reconciliation")
    }

    private class RecordingJobHistory : JobHistoryRepository {
        val cleared = mutableListOf<File>()
        val marked = mutableListOf<File>()

        override suspend fun clearSavedToGallery(photos: List<File>) { cleared += photos }
        override suspend fun markSavedToGallery(photo: File, savedAt: Long) { marked += photo }

        override suspend fun findOrStartJob(workId: String, threshold: Float): Long = unused()
        override suspend fun needsWork(jobId: Long): Boolean = unused()
        override suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>): List<FaceMatchResult> = unused()
        override suspend fun abandonJob(jobId: Long) = unused()
        override suspend fun abandonedJobs(): List<AbandonedJob> = unused()
        override suspend fun getLastJob(): SavedJob? = unused()
        override suspend fun getJob(jobId: Long): SavedJob? = unused()
        override suspend fun getAllJobSummaries(): List<JobSummary> = unused()
        override suspend fun removeMatches(photos: List<File>) = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not part of reconciliation")
    }
}
