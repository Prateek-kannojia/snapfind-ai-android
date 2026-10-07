package com.example.snapfindai.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the database guards that the job lifecycle depends on.
 *
 * These exist because reasoning about them was not enough: two external
 * audits found bugs in this area that careful rereading had missed, and the
 * remaining failure modes need a process to die at an exact instant, which
 * no amount of reading reproduces. Each test below corresponds to a specific
 * bug that was either fixed or guarded against.
 */
// Robolectric 4.13 ships images up to SDK 34, and the app targets 36, so the
// test SDK is pinned rather than inherited. Nothing here is version
// sensitive: it exercises SQL through Room, which behaves the same either way.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobDaoTest {

    private lateinit var database: SnapFindDatabase
    private lateinit var dao: JobDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            SnapFindDatabase::class.java,
        ).build()
        dao = database.jobDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insertRunningJob(workId: String) = dao.insertJob(
        JobEntity(timestamp = 1L, threshold = 0.5f, status = JobStatus.Running.name, workId = workId)
    )

    /**
     * The whole reason the job row is keyed on the work request. Creating one
     * per *attempt* meant every process kill left an orphan row that nothing
     * would ever resume, because from WorkManager's side the work had
     * succeeded.
     */
    @Test
    fun `the same work request resolves to the same job`() = runTest {
        val first = insertRunningJob("work-1")

        val found = dao.getJobIdForWork("work-1")

        assertEquals("a retry must continue the job its first attempt started", first, found)
    }

    @Test
    fun `an unknown work request has no job yet`() = runTest {
        assertNull(dao.getJobIdForWork("never-run"))
    }

    /**
     * Guards the corruption that used to be reachable: a process death
     * between writing a job's photos and marking it complete, after which the
     * retry inserted a second row for the same path. The results grid keys
     * its items by path, so duplicates throw -- and keep throwing until app
     * data is cleared.
     */
    @Test
    fun `the same photo cannot be recorded twice for one job`() = runTest {
        val jobId = insertRunningJob("work-1")
        val photo = MatchedPhotoEntity(jobId = jobId, photoPath = "/photos/a.jpg", distance = 0.4f, galleryName = "g_a.jpg")
        dao.insertMatchedPhotos(listOf(photo))

        try {
            dao.insertMatchedPhotos(listOf(photo))
            fail("the unique index should have rejected a duplicate (jobId, photoPath)")
        } catch (expected: Exception) {
            // Room surfaces the constraint violation; which exact type is
            // SQLite's business, the point is that it does not succeed.
        }
    }

    /** Completion has to be atomic, so the photos and the status are never visible apart. */
    @Test
    fun `completing a job writes its photos and its status together`() = runTest {
        val jobId = insertRunningJob("work-1")

        dao.completeJobWithPhotos(
            jobId = jobId,
            photos = listOf(MatchedPhotoEntity(jobId = jobId, photoPath = "/photos/a.jpg", distance = 0.4f, galleryName = "g_a.jpg")),
            completeStatus = JobStatus.Complete.name,
        )

        assertEquals(JobStatus.Complete.name, dao.getJobStatus(jobId))
        assertEquals(1, dao.getMatchedPhotosForJob(jobId).size)
    }

    /**
     * The first bug this area produced. The tidy-up query deletes jobs with
     * no photos, which was safe only while rows were created *after* their
     * photos existed. A running job legitimately has none yet, so without
     * the status filter this deleted jobs mid-run.
     */
    @Test
    fun `tidying up empty jobs leaves a running one alone`() = runTest {
        val running = insertRunningJob("work-1")
        val finishedButEmpty = dao.insertJob(
            JobEntity(timestamp = 2L, threshold = 0.5f, status = JobStatus.Complete.name, workId = "work-2")
        )

        dao.deleteEmptyJobs(JobStatus.Complete.name)

        assertNotNull("a job still running must survive, photos or not", dao.getJobStatus(running))
        assertNull("a finished job with no photos is junk", dao.getJobStatus(finishedButEmpty))
    }

    @Test
    fun `only running jobs are reported as abandoned`() = runTest {
        insertRunningJob("work-1")
        dao.insertJob(JobEntity(timestamp = 2L, threshold = 0.5f, status = JobStatus.Complete.name, workId = "work-2"))

        val abandoned = dao.getJobsWithStatus(JobStatus.Running.name)

        assertEquals(1, abandoned.size)
        assertEquals("work-1", abandoned.single().workId)
    }

    /**
     * Results and the photo viewer each query this independently for the same
     * job, and navigation used to pass a *position* between them. Without a
     * deterministic order that lands on the wrong photo; SQLite promises no
     * ordering unless asked.
     */
    @Test
    fun `matched photos come back in insertion order`() = runTest {
        val jobId = insertRunningJob("work-1")
        val paths = listOf("/photos/c.jpg", "/photos/a.jpg", "/photos/b.jpg")
        dao.insertMatchedPhotos(paths.map { MatchedPhotoEntity(jobId = jobId, photoPath = it, distance = 0.4f, galleryName = "g_$it") })

        val returned = dao.getMatchedPhotosForJob(jobId).map { it.photoPath }

        assertEquals("insertion order is ZIP order, which is what the grid shows", paths, returned)
    }

    /** Deleting a job has to take its photos with it, or the next job id reuse inherits them. */
    @Test
    fun `deleting a job cascades to its photos`() = runTest {
        val jobId = insertRunningJob("work-1")
        dao.insertMatchedPhotos(listOf(MatchedPhotoEntity(jobId = jobId, photoPath = "/photos/a.jpg", distance = 0.4f, galleryName = "g_a.jpg")))

        dao.deleteJob(jobId)

        assertEquals(emptyList<MatchedPhotoEntity>(), dao.getMatchedPhotosForJob(jobId))
    }
}
