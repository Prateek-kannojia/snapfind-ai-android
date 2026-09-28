package com.example.snapfindai.facesdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    private fun download(url: String, dest: File, onProgress: ((Long, Long) -> Unit)?) {
        val connection = URL(url).openConnection()
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, total)
                }
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
