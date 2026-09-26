package com.example.snapfindai.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object FileHelper {
    fun uriToFile(context: Context, uri: Uri, fileName: String): File? {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.cacheDir, fileName)
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
