package com.example.snapfindai.facesdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a model file into local storage and caches it there, verifying
 * its SHA-256 checksum so a corrupted or tampered download can never
 * silently feed a wrong model into a detector/embedder. An already-cached,
 * already-verified file is never re-downloaded.
 *
 * Exists for hosts that don't want the ~16MB of bundled model weights
 * inflating every install's APK (see facesdk's own debug-only assets --
 * they exist for local development/testing, not for a shipped release
 * build) -- fetch them once on first launch instead, cache them, and load
 * with [com.example.snapfindai.facesdk.api.FaceDetector.createFromFile] /
 * [com.example.snapfindai.facesdk.api.FaceEmbedder.createFromFile].
 */
object ModelDownloader {

    /** Generous enough for a slow connection, short enough that a dead one fails rather than hanging. */
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    class ChecksumMismatchException(message: String) : Exception(message)

    /** Cached under the app's private files dir -- not user-visible, cleared if the app's storage is cleared (which is correct: it'll just re-download). */
    suspend fun getOrDownload(
        context: Context,
        url: String,
        fileName: String,
        sha256: String,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
    ): File = getOrDownload(url, File(context.filesDir, "facesdk_models"), fileName, sha256, onProgress)

    /**
     * Checks whether a valid, checksum-verified copy is already cached --
     * without downloading anything. Lets a caller decide up front whether a
     * download is needed at all (e.g. to skip a first-run download screen
     * on every launch after the first).
     */
    suspend fun isCached(context: Context, fileName: String, sha256: String): Boolean =
        isCached(File(context.filesDir, "facesdk_models"), fileName, sha256)

    /** Same as the [Context]-based overload, but takes the cache directory directly. */
    suspend fun isCached(destDir: File, fileName: String, sha256: String): Boolean =
        withContext(Dispatchers.IO) {
            val dest = File(destDir, fileName)
            dest.exists() && sha256Of(dest).equals(sha256, ignoreCase = true)
        }

    /**
     * Same as the [Context]-based overload, but takes the cache directory
     * directly -- what the app-facing API delegates to, and what tests call
     * against a temp directory without needing a real Android [Context].
     */
    suspend fun getOrDownload(
        url: String,
        destDir: File,
        fileName: String,
        sha256: String,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val dest = File(destDir, fileName)

        if (dest.exists() && sha256Of(dest).equals(sha256, ignoreCase = true)) {
            return@withContext dest
        }

        // Downloaded to a temp name and only renamed once the checksum passes,
        // so an interrupted download can never leave a half-written file under
        // the real name for a later run to load as a valid model. Deleted on
        // the way out of a failure or cancellation, rather than leaving ~16MB
        // of dead weight behind until the next attempt overwrites it.
        val tempFile = File(destDir, "$fileName.download")
        try {
            download(url, tempFile, onProgress)
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }

        val actualSha256 = sha256Of(tempFile)
        if (!actualSha256.equals(sha256, ignoreCase = true)) {
            tempFile.delete()
            throw ChecksumMismatchException(
                "Downloaded '$fileName' does not match the expected checksum " +
                    "(expected $sha256, got $actualSha256) -- discarded, not cached."
            )
        }

        dest.delete()
        if (!tempFile.renameTo(dest)) {
            tempFile.copyTo(dest, overwrite = true)
            tempFile.delete()
        }
        dest
    }

    /**
     * Suspending so the transfer can be cancelled part-way. A read/write loop
     * has no suspension points of its own, so without the per-chunk check a
     * caller cancelling this would keep downloading every remaining byte
     * before anything noticed -- which for a ~16MB model over a slow
     * connection makes a cancel button decorative.
     */
    private suspend fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)?) {
        // Without these the defaults are unlimited, so a connection that
        // stalls rather than failing leaves the download hanging forever --
        // and the per-chunk cancellation check below never runs, because the
        // read it's guarding never returns.
        val connection = URL(url).openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val total = connection.contentLengthLong

        connection.getInputStream().use { input ->
            // Timeouts alone would mean a cancel sits unnoticed until the
            // current read times out. Closing the stream from the outside
            // when this job is cancelled makes the blocked read throw at
            // once, which is the only way to interrupt blocking I/O.
            val closeOnCancel = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause != null) runCatching { input.close() }
            }
            try {
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, total)
                    }
                }
            } finally {
                closeOnCancel.dispose()
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
