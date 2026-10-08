package com.example.snapfindai.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object FileHelper {

    /**
     * The parent of every run's scratch directory. Nothing is ever written
     * directly here, and nothing should ever delete it wholesale -- see
     * [requestWorkDir].
     *
     * Deliberately NOT cacheDir, for the same reason JobHistoryRepositoryImpl
     * keeps saved matches out of it -- the OS empties cacheDir under storage
     * pressure, whenever it likes, including halfway through a job. A job
     * that writes a multi-gigabyte archive is itself enough to trigger that,
     * and the files it deleted first were the ones we were about to read.
     */
    fun jobWorkDir(context: Context): File =
        File(context.filesDir, "job_work").apply { mkdirs() }

    /**
     * Scratch space belonging to one background work request: its copied
     * selfie, its copied ZIP, and the photos out of that archive that matched.
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
     * Writes [bytes] to [dest] so that the file is either complete or absent,
     * never half-written.
     *
     * Written under a temporary name and renamed into place, because a rename
     * within a filesystem is atomic while a write is not. A file sitting at
     * [dest] is therefore complete *by construction* -- which matters because
     * a matched photo is named by a checkpoint row the moment it is written,
     * and a process killed mid-write would otherwise leave a truncated photo
     * that every later check accepts as present.
     */
    fun writeAtomically(dest: File, bytes: ByteArray) {
        val partFile = File(dest.path + PART_SUFFIX)
        FileOutputStream(partFile).use { output -> output.write(bytes) }
        if (!partFile.renameTo(dest)) {
            // A rename onto an existing name is allowed to fail on some
            // filesystems; clearing the way is safe here because the only
            // thing it can be is an identical photo from an attempt that was
            // killed before it could record this one.
            dest.delete()
            if (!partFile.renameTo(dest)) {
                partFile.delete()
                throw IOException("Couldn't save ${dest.name}.")
            }
        }
    }

    /** What a half-written file is called while it is being written -- never a photo extension, so nothing picks one up as an image. */
    private const val PART_SUFFIX = ".part"
}
