package com.example.snapfindai.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object FileHelper {

    /**
     * Scratch space for one matching run: the copied selfie, the copied ZIP,
     * and everything extracted out of it.
     *
     * Deliberately NOT cacheDir, for the same reason JobHistoryRepositoryImpl
     * keeps saved matches out of it -- the OS empties cacheDir under storage
     * pressure, whenever it likes, including halfway through a job. Extracting
     * a multi-gigabyte event folder is itself enough to trigger that, and the
     * files it deleted first were the ones we were about to read.
     *
     * The trade is that nothing clears this for us, so [prepareJobWorkDir]
     * wipes it at the start of every run and FindFacesInPhotosUseCase clears
     * it when it finishes.
     */
    fun jobWorkDir(context: Context): File =
        File(context.filesDir, "job_work").apply { mkdirs() }

    /**
     * Empties [jobWorkDir] and hands it back ready to use. Called when a run
     * starts, so a previous run killed mid-flight (process death, force quit)
     * can't leave gigabytes of extracted photos behind permanently.
     */
    fun prepareJobWorkDir(context: Context): File {
        val dir = jobWorkDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /** Blocking disk I/O -- callers are responsible for being off the main thread. */
    fun uriToFile(context: Context, uri: Uri, fileName: String): File? {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(jobWorkDir(context), fileName)
        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    /**
     * Extracts every file entry from [zipFile] into [destDir], returning the
     * extracted files. Guards against "zip slip" (an entry named e.g.
     * "../../evil") writing outside [destDir] -- the zip comes from
     * whatever the user picked, but there's no reason to trust its entry
     * names any more than we'd trust a downloaded one.
     *
     * Blocking disk I/O, and for a real event folder that means a lot of it
     * -- callers are responsible for being off the main thread.
     */
    fun unzip(zipFile: File, destDir: File): List<File> {
        destDir.mkdirs()
        val destCanonicalPath = destDir.canonicalPath
        val extracted = mutableListOf<File>()

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name).canonicalFile
                    if (!outFile.path.startsWith(destCanonicalPath + File.separator)) {
                        throw SecurityException("Zip entry outside target directory: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zis.copyTo(output) }
                    extracted += outFile
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }
}
