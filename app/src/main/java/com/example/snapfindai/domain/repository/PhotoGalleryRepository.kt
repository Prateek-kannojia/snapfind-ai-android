package com.example.snapfindai.domain.repository

import android.net.Uri
import java.io.File

/** Saves a photo into the device's shared gallery (visible in Photos/Gallery/Files, outside this app). */
interface PhotoGalleryRepository {
    /**
     * Returns the saved photo's content Uri when the platform provides one
     * (Android 10+, via MediaStore) -- null on older versions, where the
     * file is written directly into the public Pictures directory instead
     * and there's no equivalent Uri to hand back.
     * @throws Exception if the save fails for any reason (denied permission on API < 29, I/O error, ...).
     */
    suspend fun saveToGallery(photo: File): Uri?
}
