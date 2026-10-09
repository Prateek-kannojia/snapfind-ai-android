package com.example.snapfindai.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.snapfindai.domain.model.AbandonedJob
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.JobCheckpoint
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.model.SavedJob
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.utils.FileHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The sweeper deletes a job's working files, so every test here is really
 * asking one question: does it ever delete something a job was going to come
 * back for?
 *
 * It answers that by asking WorkManager whether the request owning each
 * leftover is still live, which is why these run against a *real* WorkManager
 * on an in-memory database rather than a stub. A stub would answer the one
 * question under test, and the test would be checking the stub.
 *
 * An earlier version used a timeout instead -- "how old is this?" as a proxy
 * for "is anything coming for it?" -- and took one liveness snapshot before
 * emptying a shared directory, which could destroy the inputs of a job
 * started while it ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AbandonedJobSweeperTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        FileHelper.jobWorkDir(context).deleteRecursively()
    }

    /**
     * The one that matters. A request still waiting to run owns files its next
     * attempt resumes from; deleting them turns a resumable job into a lost
     * one, and the user would see a job that restarted from zero.
     */
    @Test
    fun `a directory whose work is still coming is left alone`() = runTest {
        val live = enqueueWorkThatHasNotRunYet()
        val directory = workingDirectoryFor(live.id)

        sweeper().sweep()

        assertTrue("a live request's files must survive the sweep", directory.exists())
    }

    @Test
    fun `a directory whose work has finished is deleted`() = runTest {
        val finished = enqueueWorkThatRunsImmediately()
        val directory = workingDirectoryFor(finished.id)

        sweeper().sweep()

        assertFalse("nothing is coming back for a finished request", directory.exists())
    }

    /**
     * Why the sweep is driven by directories rather than database rows: a run
     * cancelled before its worker ever started has a directory and no row, so
     * a row-driven sweep would never see it.
     */
    @Test
    fun `a directory belonging to no work request at all is deleted`() = runTest {
        val orphan = workingDirectoryFor(UUID.randomUUID())

        sweeper().sweep()

        assertFalse("a directory no request owns is unreachable by anything else", orphan.exists())
    }

    @Test
    fun `a directory whose name is not a request id is deleted`() = runTest {
        val strange = File(FileHelper.jobWorkDir(context), "not-a-uuid").apply { mkdirs() }

        sweeper().sweep()

        assertFalse(strange.exists())
    }

    /**
     * The second pass, for a run that got far enough to clear its files but
     * not to resolve its own status. Left alone these sit as permanently
     * "running" jobs, which the history tidy-up deliberately refuses to touch.
     */
    @Test
    fun `a job left running with no work behind it is abandoned`() = runTest {
        val finished = enqueueWorkThatRunsImmediately()
        val history = FakeHistory(AbandonedJob(jobId = 1L, workId = finished.id.toString()))

        AbandonedJobSweeper(context, history, workManager).sweep()

        assertEquals(listOf(1L), history.abandoned)
    }

    /** The same mistake as deleting a live request's files, one table over. */
    @Test
    fun `a job whose work is still coming keeps its row`() = runTest {
        val live = enqueueWorkThatHasNotRunYet()
        val history = FakeHistory(AbandonedJob(jobId = 1L, workId = live.id.toString()))

        AbandonedJobSweeper(context, history, workManager).sweep()

        assertEquals("the job is interrupted, not abandoned", emptyList<Long>(), history.abandoned)
    }

    @Test
    fun `a job that was never linked to a request is abandoned`() = runTest {
        val history = FakeHistory(AbandonedJob(jobId = 1L, workId = null))

        AbandonedJobSweeper(context, history, workManager).sweep()

        assertEquals(listOf(1L), history.abandoned)
    }

    private fun sweeper() = AbandonedJobSweeper(context, FakeHistory(), workManager)

    private fun workingDirectoryFor(id: UUID): File =
        FileHelper.requestWorkDir(context, id.toString())
            .also { File(it, "events.zip").writeText("an input a resumed job would need") }

    /** An initial delay leaves it ENQUEUED: accepted, not finished, and something is still coming for it. */
    private fun enqueueWorkThatHasNotRunYet(): WorkRequest =
        OneTimeWorkRequestBuilder<NoopWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
            .also { workManager.enqueue(it).result.get() }

    /** The synchronous executor runs it before enqueue returns, so it is SUCCEEDED by the time the sweep looks. */
    private fun enqueueWorkThatRunsImmediately(): WorkRequest =
        OneTimeWorkRequestBuilder<NoopWorker>()
            .build()
            .also { workManager.enqueue(it).result.get() }

    /** Stands in for the real worker: the sweeper cares about a request's state, never about what it runs. */
    class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private class FakeHistory(private vararg val running: AbandonedJob) : JobHistoryRepository {
        val abandoned = mutableListOf<Long>()

        override suspend fun abandonedJobs(): List<AbandonedJob> = running.toList()
        override suspend fun abandonJob(jobId: Long) { abandoned += jobId }

        override suspend fun findOrStartJob(workId: String, threshold: Float): Long = unused()
        override suspend fun needsWork(jobId: Long): Boolean = unused()
        override suspend fun completeJob(jobId: Long, matches: List<FaceMatchResult>): List<FaceMatchResult> = unused()
        override suspend fun deleteJob(jobId: Long) = unused()
        override suspend fun checkpointFor(jobId: Long): JobCheckpoint = unused()
        override suspend fun recordScored(jobId: Long, scoredCount: Int, match: FaceMatchResult?) = unused()
        override suspend fun getLastJob(): SavedJob? = unused()
        override suspend fun getJob(jobId: Long): SavedJob? = unused()
        override suspend fun getAllJobSummaries(): List<JobSummary> = unused()
        override suspend fun markSavedToGallery(photo: File, savedAt: Long) = unused()
        override suspend fun clearSavedToGallery(photos: List<File>) = unused()
        override suspend fun removeMatches(photos: List<File>) = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not part of sweeping")
    }
}
