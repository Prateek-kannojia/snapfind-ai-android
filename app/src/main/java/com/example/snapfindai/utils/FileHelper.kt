package com.example.snapfindai.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

object FileHelper {

    /**
     * The parent of every run's scratch directory. Nothing is ever written
     * directly here, and nothing should ever delete it wholesale -- see
     * [requestWorkDir].
     *
     * Deliberately NOT cacheDir, for the same reason JobHistoryRepositoryImpl
     * keeps saved matches out of it -- the OS empties cacheDir under storage
     * pressure, whenever it likes, including halfway through a job. Extracting
     * a multi-gigabyte event folder is itself enough to trigger that, and the
     * files it deleted first were the ones we were about to read.
     */
    fun jobWorkDir(context: Context): File =
        File(context.filesDir, "job_work").apply { mkdirs() }

    /**
     * Scratch space belonging to one background work request: its copied
     * selfie, its copied ZIP, and everything extracted out of it.
     *
     * Named by the request id, which is what makes it safe. Runs previously
     * shared `job_work/temp_selfie.jpg` and `temp_events.zip`, and cancelling
     * a work request only records the cancellation -- it doesn't wait for the
     * worker to stop. So a new run could overwrite a still-running one's
     * inputs, and that worker's cleanup would then delete the *new* run's
     * files. One directory per request makes both impossible: no two runs can
     * name the same file, and cleanup can only ever reach its own.
     *
     * Derivable on both sides from the request id alone, so the path never
     * has to be passed around -- which also means a worker cannot be handed
     * a path belonging to anyone else.
     */
    fun requestWorkDir(context: Context, requestId: String): File =
        File(jobWorkDir(context), requestId).apply { mkdirs() }

    /** Blocking disk I/O -- callers are responsible for being off the main thread. */
    fun uriToFile(context: Context, uri: Uri, destination: File): File? {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        return destination
    }

    /**
     * Extracts every file entry from [zipFile] into [destDir], returning the
     * extracted files. Guards against "zip slip" (an entry named e.g.
     * "../../evil") writing outside [destDir] -- the zip comes from
     * whatever the user picked, but there's no reason to trust its entry
     * names any more than we'd trust a downloaded one.
     *
     * Blocking disk I/O, and for a real event folder that means a lot of it
     * -- callers are responsible for being off the main thread. Suspending
     * only so it can check for cancellation between entries: extracting
     * 500MB is otherwise one uninterruptible block, and "cancel" during it
     * would do nothing until the whole archive had been written out.
     *
     * **Resumable.** Each entry is written under a temporary name and only
     * then renamed into place, so a file that exists under its real name is
     * known to be complete -- and an entry whose file is already there is
     * skipped. Calling this again after a process kill therefore costs the
     * inflate but not the write for everything the previous attempt finished,
     * instead of overwriting the lot.
     *
     * The rename is what makes the skip safe. Writing directly would leave a
     * truncated file looking exactly like a finished one, and the next attempt
     * would skip it and then try to decode a half-written photo.
     */
    suspend fun unzip(zipFile: File, destDir: File, maxTotalBytes: Long): List<File> {
        destDir.mkdirs()
        val destCanonicalPath = destDir.canonicalPath
        val extracted = mutableListOf<File>()
        var totalBytes = 0L

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                currentCoroutineContext().ensureActive()
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name).canonicalFile
                    if (!outFile.path.startsWith(destCanonicalPath + File.separator)) {
                        throw SecurityException("Zip entry outside target directory: ${entry.name}")
                    }
                    if (outFile.exists()) {
                        // Left by an earlier attempt, and complete by
                        // construction -- only the rename below creates this
                        // name. Counted toward the expansion guard like any
                        // other extracted byte, so resuming can't be a way
                        // around it.
                        extracted += outFile
                        totalBytes += outFile.length()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val partFile = File(outFile.path + PART_SUFFIX)
                        FileOutputStream(partFile).use { output -> zis.copyTo(output) }
                        if (!partFile.renameTo(outFile)) {
                            partFile.delete()
                            throw IOException("Couldn't finish extracting ${entry.name}.")
                        }
                        extracted += outFile
                        totalBytes += outFile.length()
                    }

                    // The zip-slip check above stops an archive writing
                    // outside this directory; it does nothing about one that
                    // expands far beyond its own size. Photos barely
                    // compress, so wildly exceeding the archive's size means
                    // this isn't a photo archive, and continuing would fill
                    // the device.
                    if (totalBytes > maxTotalBytes) {
                        throw IOException(
                            "That ZIP expands to far more than its own size, which doesn't look like a photo archive."
                        )
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    /**
     * Whether there is room to extract [zipFile] alongside itself.
     *
     * Photos are already compressed, so an archive of them extracts to
     * roughly its own size; the margin covers the relocation of matched
     * photos afterwards. Checked up front so a folder that cannot fit fails
     * in a second with something actionable, rather than part-way through
     * extraction with a raw I/O error and a half-filled device.
     *
     * Measured from the ZIP itself, which exists, rather than from the
     * directory about to receive it, which does not yet. `usableSpace`
     * answers 0 for a path that names no partition, so asking a
     * not-yet-created directory reported no free space on any device and
     * rejected every job. Both live under filesDir, so it is the same
     * partition either way.
     */
    fun hasRoomToExtract(zipFile: File): Boolean =
        zipFile.usableSpace >= requiredSpaceToExtract(zipFile)

    /** Bytes that need to be free for [zipFile] to unpack, margin included. */
    fun requiredSpaceToExtract(zipFile: File): Long =
        (zipFile.length() * EXTRACTION_SPACE_MARGIN).toLong()

    /** How far an archive may expand beyond its own size before it stops looking like photos. */
    const val MAX_EXPANSION_FACTOR = 4

    /**
     * What a half-written entry is called. Not a photo extension, so a
     * leftover one is filtered out by every caller that looks for photos
     * rather than being handed to a decoder.
     */
    private const val PART_SUFFIX = ".part"

    private const val EXTRACTION_SPACE_MARGIN = 1.3
}
