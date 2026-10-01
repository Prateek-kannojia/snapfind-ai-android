package com.example.snapfindai.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.snapfindai.domain.repository.GallerySaveResult
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

    private val galleryRelativePath = "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER"
    private val legacyGalleryDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), GALLERY_FOLDER)

    /**
     * Derived from the source file, never from the clock: the same photo
     * always maps to the same gallery entry, so saving it again is a no-op
     * instead of a second copy. The path hash keeps two different jobs'
     * "IMG_1234.jpg" from colliding on one name.
     */
    override fun displayNameFor(photo: File): String =
        "SnapFindAI_${Integer.toHexString(photo.absolutePath.hashCode())}_${photo.name}"

    override suspend fun saveToGallery(photo: File): GallerySaveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(photo)
        } else {
            saveViaLegacyPublicDirectory(photo)
        }
    }

    override suspend fun savedDisplayNames(): Set<String>? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreDisplayNames()
        } else if (hasLegacyStoragePermission()) {
            // No directory yet simply means nothing has been saved, which is
            // an empty set -- not the "couldn't tell" null below.
            legacyGalleryDir.list()?.toSet() ?: emptySet()
        } else {
            null
        }
    }

    /**
     * Android 10+ (scoped storage): MediaStore insert, no permission needed
     * at all -- an app can always read back and write the entries it owns.
     */
    private fun saveViaMediaStore(photo: File): GallerySaveResult {
        val displayName = displayNameFor(photo)
        existingUri(displayName)?.let { return GallerySaveResult(uri = it, alreadyExisted = true) }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, galleryRelativePath)
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
            return GallerySaveResult(uri = uri, alreadyExisted = false)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a half-written, permanently-pending entry behind
            throw e
        }
    }

    /**
     * A trashed photo (Android 11+ "Bin", where Google Photos puts deletions
     * for 30-60 days) is deliberately not matched here: MediaStore excludes
     * trashed rows from a plain query, so a binned photo reads as gone and
     * gets its tick cleared -- and re-appears if the user restores it.
     */
    private fun existingUri(displayName: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(displayName, "$galleryRelativePath%")
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    private fun mediaStoreDisplayNames(): Set<String> {
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$galleryRelativePath%")
        val names = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) names += cursor.getString(nameColumn)
        }
        return names
    }

    /**
     * Android 9 (API 28) and below: no MediaStore.RELATIVE_PATH, no scoped
     * storage -- write straight into the public Pictures directory (needs
     * WRITE_EXTERNAL_STORAGE, declared in the manifest with
     * maxSdkVersion="28") and trigger a media scan so it shows up in the
     * gallery immediately instead of after the next reboot.
     */
    private fun saveViaLegacyPublicDirectory(photo: File): GallerySaveResult {
        if (!hasLegacyStoragePermission()) {
            throw SecurityException("WRITE_EXTERNAL_STORAGE not granted -- required to save photos on this Android version")
        }

        val picturesDir = legacyGalleryDir
        picturesDir.mkdirs()
        val dest = File(picturesDir, displayNameFor(photo))
        if (dest.exists()) return GallerySaveResult(uri = null, alreadyExisted = true)

        photo.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
        return GallerySaveResult(uri = null, alreadyExisted = false)
    }

    private fun hasLegacyStoragePermission() = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val GALLERY_FOLDER = "SnapFindAI"
    }
}
