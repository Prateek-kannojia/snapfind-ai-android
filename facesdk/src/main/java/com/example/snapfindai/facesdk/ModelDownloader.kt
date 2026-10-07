package com.example.snapfindai.facesdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
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
        // the real name for a later run to load as a valid model.
        //
        // A partial file is deliberately left in place when this fails or is
        // cancelled: [download] resumes from it, so a pause or a dropped
        // connection costs the user nothing it doesn't have to. A caller that
        // means "forget it entirely" says so by calling [discardPartial].
        val tempFile = File(destDir, "$fileName.download")
        download(url, tempFile, onProgress)

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
     *
     * Picks up where a previous attempt left off: whatever [dest] already
     * holds is asked for as a byte range rather than downloaded again, so
     * pausing at 90% and resuming costs the last 10% and not the whole file.
     * If the server ignores the range -- or anything else about the response
     * says those bytes can't be trusted to line up -- the file is rewritten
     * from zero, because appending to a mismatched prefix would produce a
     * plausible-looking file that fails its checksum for no visible reason.
     */
    private suspend fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)?) {
        val alreadyHave = if (dest.exists()) dest.length() else 0L

        // Without these the defaults are unlimited, so a connection that
        // stalls rather than failing leaves the download hanging forever --
        // and the per-chunk cancellation check below never runs, because the
        // read it's guarding never returns.
        val connection = URL(url).openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        // Ranges are an HTTP idea. Any other scheme has no way to ask for
        // part of a file, so it simply starts over -- correct, just slower.
        val http = connection as? HttpURLConnection
        if (alreadyHave > 0) http?.setRequestProperty("Range", "bytes=$alreadyHave-")

        try {
            // 206 is the only answer that means "here is the rest of it".
            // A 200 means the range was ignored and the body is the whole
            // file again, which is a slower but entirely correct outcome.
            val resuming = alreadyHave > 0 && http?.responseCode == HttpURLConnection.HTTP_PARTIAL
            val startAt = if (resuming) alreadyHave else 0L
            val remaining = connection.contentLengthLong
            // -1 when the server doesn't say; progress reporting treats a
            // non-positive total as "unknown" rather than dividing by it.
            val total = if (remaining >= 0) startAt + remaining else -1L

            connection.getInputStream().use { input ->
                // Timeouts alone would mean a cancel sits unnoticed until the
                // current read times out. Closing the stream from the outside
                // when this job is cancelled makes the blocked read throw at
                // once, which is the only way to interrupt blocking I/O.
                val closeOnCancel = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { input.close() }
                }
                try {
                    // append = resuming, so the one path that keeps existing
                    // bytes is the one that asked the server to skip them.
                    FileOutputStream(dest, resuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = startAt
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress?.invoke(downloaded, total)
                        }
                        // Bytes written here are what a later resume will
                        // trust, so they have to be on disk and not in a
                        // buffer that a process death takes with it.
                        output.flush()
                        output.fd.sync()
                    }
                } finally {
                    closeOnCancel.dispose()
                }
            }
        } finally {
            http?.disconnect()
        }
    }

    /**
     * Throws away any partially downloaded copy of [fileName], so the next
     * attempt starts from zero instead of resuming.
     *
     * The counterpart to the resume behaviour above: a paused download keeps
     * its bytes, an abandoned one shouldn't sit in the user's storage waiting
     * for a download that may never be retried.
     */
    suspend fun discardPartial(context: Context, fileName: String) =
        discardPartial(File(context.filesDir, "facesdk_models"), fileName)

    /** Same as the [Context]-based overload, but takes the cache directory directly. */
    suspend fun discardPartial(destDir: File, fileName: String) {
        withContext(Dispatchers.IO) { File(destDir, "$fileName.download").delete() }
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
