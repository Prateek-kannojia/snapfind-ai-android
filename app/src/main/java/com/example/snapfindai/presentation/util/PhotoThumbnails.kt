package com.example.snapfindai.presentation.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.buffer
import okio.source
import java.io.File
import java.security.MessageDigest

/**
 * A grid tile's model: "the small version of this photo".
 *
 * The grids render the matched photos themselves, which are whatever came
 * out of the user's camera -- several megabytes, up to 12 megapixels. Coil
 * downsamples them to the tile, but to do that it still reads and decodes
 * the whole file, tens of milliseconds each. On a fling four of those arrive
 * at once, so tiles pop in late and the large intermediate bitmaps churn the
 * heap. That is the jitter.
 *
 * Instagram and YouTube are handed pre-sized thumbnails; this app was
 * decoding originals. [ThumbnailFetcher] makes one small copy per photo, the
 * first time it is shown, and reads that afterwards.
 */
data class Thumbnail(val photo: File)

/**
 * Produces (and then reuses) a small copy of a photo for grid display.
 *
 * **Display only.** The viewer opens the original and the gallery is given
 * the original -- a thumbnail is never what the user ends up with.
 *
 * Falls back to the original file whenever anything goes wrong. This sits in
 * the path that draws every photo in the app, so the worst case has to be
 * "as slow as it used to be", never "no photos".
 */
class ThumbnailFetcher(
    private val model: Thumbnail,
    private val options: Options,
    private val cacheDir: File,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val source = thumbnailOf(model.photo) ?: model.photo
        return SourceResult(
            source = ImageSource(source.source().buffer(), options.context),
            mimeType = "image/jpeg",
            // DISK rather than MEMORY: this came off storage, and telling
            // Coil otherwise would have it skip its own memory cache.
            dataSource = DataSource.DISK,
        )
    }

    private fun thumbnailOf(photo: File): File? = runCatching {
        val cached = File(cacheDir, cacheNameFor(photo))
        if (cached.exists() && cached.lastModified() >= photo.lastModified()) return cached

        // Bounds first, so the sample size is chosen before anything is
        // allocated -- the whole point is never to hold the full bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, bounds)
        val decoded = BitmapFactory.decodeFile(
            photo.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            },
        ) ?: return null

        cacheDir.mkdirs()
        // Written under a temporary name and renamed, the same rule as every
        // other file this app writes: a half-written thumbnail left by a
        // killed process would otherwise be read as a finished one.
        val part = File(cached.path + ".part")
        part.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        decoded.recycle()
        if (!part.renameTo(cached)) {
            cached.delete()
            if (!part.renameTo(cached)) {
                part.delete()
                return null
            }
        }
        cached
    }.getOrNull()

    /**
     * Keyed by the photo's full path, hashed: two jobs can hold photos with
     * the same filename, and a thumbnail named after the filename alone
     * would show one job's photo inside another's card.
     */
    private fun cacheNameFor(photo: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(photo.absolutePath.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /** Powers of two because BitmapFactory rounds anything else down to one anyway. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (width / sampleSize > TARGET_EDGE && height / sampleSize > TARGET_EDGE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    class Factory(private val context: Context) : Fetcher.Factory<Thumbnail> {
        private val cacheDir = File(context.cacheDir, "thumbnails")

        override fun create(data: Thumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, options, cacheDir)
    }

    private companion object {
        /**
         * Comfortably above a grid tile on a dense phone, so a thumbnail is
         * never visibly soft, and far below the original -- which is the
         * whole saving. Kept in cacheDir: the OS may reclaim these, and the
         * only cost of that is generating them again.
         */
        const val TARGET_EDGE = 600
        const val QUALITY = 80
    }
}
