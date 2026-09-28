package com.example.snapfindai.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreGalleryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoGalleryRepository {

    override suspend fun saveToGallery(photo: File): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(photo)
        } else {
            saveViaLegacyPublicDirectory(photo)
            null
        }
    }

    /** Android 10+ (scoped storage): MediaStore insert, no permission needed at all. */
    private fun saveViaMediaStore(photo: File): Uri {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayNameFor(photo))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SnapFindAI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("MediaStore refused to create an entry for ${photo.name}")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                photo.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Could not open an output stream for $uri")
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-written, permanently-pending entry behind
            throw e
        }
    }

    /**
     * Android 9 (API 28) and below: no MediaStore.RELATIVE_PATH, no scoped
     * storage -- write straight into the public Pictures directory (needs
     * WRITE_EXTERNAL_STORAGE, declared in the manifest with
     * maxSdkVersion="28") and trigger a media scan so it shows up in the
     * gallery immediately instead of after the next reboot.
     */
    private fun saveViaLegacyPublicDirectory(photo: File) {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE not granted -- required to save photos on this Android version")
        }

        val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SnapFindAI")
        picturesDir.mkdirs()
        val dest = File(picturesDir, displayNameFor(photo))
        photo.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
    }

    private fun displayNameFor(photo: File) = "SnapFindAI_${System.currentTimeMillis()}_${photo.name}"
}
