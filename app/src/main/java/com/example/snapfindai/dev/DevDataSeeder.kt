package com.example.snapfindai.dev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Fills the job history with fake finished jobs, so the screens that only
 * appear *after* a job can be worked on without running one.
 *
 * Exercising the grid, selection, multi-delete, the size totals and the
 * download batch otherwise meant producing real matches first: picking a
 * selfie, zipping photos, and waiting minutes per job -- for a change to a
 * card's padding. Ten jobs of varying size appear instantly instead.
 *
 * **Debug builds only**, enforced at the call site in `SnapFindApplication`
 * with a `BuildConfig.DEBUG` check. That is a compile-time constant, so R8
 * removes the branch -- and with nothing else referencing this class, the
 * class itself -- from a release build.
 *
 * Seeds through [JobHistoryRepository] rather than writing rows and files
 * directly. A seeder with its own idea of where photos live and what a
 * gallery name looks like would drift from the real thing and start lying
 * about the very UI it exists to test; going through `completeJob` means
 * these jobs are relocated, hashed and recorded exactly like real ones.
 */
@Singleton
class DevDataSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobHistoryRepository: JobHistoryRepository,
) {
    /**
     * Does nothing if any job already exists, so it seeds once on a fresh
     * install and never fights with real jobs or duplicates itself on every
     * launch. Clear the app's data to get a fresh set.
     */
    suspend fun seedIfEmpty(jobCount: Int = 10) = withContext(Dispatchers.IO) {
        if (jobHistoryRepository.getAllJobSummaries().isNotEmpty()) return@withContext

        // Its own directory, deleted on the way out: completeJob *moves* what
        // it is given, so these files exist only long enough to be relocated.
        val staging = File(context.cacheDir, "dev_seed").apply { mkdirs() }
        try {
            repeat(jobCount) { jobIndex ->
                val photoCount = PHOTO_COUNTS[jobIndex % PHOTO_COUNTS.size]
                val jobId = jobHistoryRepository.findOrStartJob(
                    workId = "dev-seed-$jobIndex-${System.nanoTime()}",
                    threshold = 0.6f,
                )
                val matches = (1..photoCount).map { photoIndex ->
                    val file = File(staging, "job${jobIndex + 1}_photo$photoIndex.jpg")
                    file.writeBytes(fakePhoto(jobIndex, photoIndex))
                    FaceMatchResult(
                        photo = file,
                        // Spread across the range a real match falls in, so
                        // anything that ever sorts or filters on distance has
                        // something other than one repeated value to work on.
                        distance = 0.30f + Random.nextFloat() * 0.25f,
                    )
                }
                jobHistoryRepository.completeJob(jobId, matches)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * A flat colour with its job and photo number on it, which is the whole
     * requirement: the grid has to show *something* distinguishable, and two
     * tiles that look alike make a selection bug invisible.
     *
     * Sized like a phone photo rather than a thumbnail, deliberately -- a
     * seeded grid that decoded 40KB images would feel smoother than the real
     * one and hide exactly the scrolling problems this exists to let you see.
     */
    private fun fakePhoto(jobIndex: Int, photoIndex: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(HUES[(jobIndex * 3 + photoIndex) % HUES.size])

        val label = Paint().apply {
            color = Color.WHITE
            textSize = PHOTO_WIDTH / 6f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${jobIndex + 1}-$photoIndex", PHOTO_WIDTH / 2f, PHOTO_HEIGHT / 2f, label)

        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        /** Uneven on purpose: a grid of ten identical jobs says nothing about how the layout handles a one-photo job next to a forty-photo one. */
        val PHOTO_COUNTS = intArrayOf(12, 4, 27, 1, 8, 40, 3, 16, 6, 21)
        val HUES = intArrayOf(
            0xFFE8785A.toInt(), 0xFF8FB9A8.toInt(), 0xFFE0C097.toInt(),
            0xFF6D8B96.toInt(), 0xFFC98986.toInt(), 0xFF9CAF88.toInt(),
        )
        const val PHOTO_WIDTH = 1200
        const val PHOTO_HEIGHT = 1600
    }
}
