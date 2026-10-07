package com.example.snapfindai.domain.repository

import android.net.Uri
import java.io.File

/**
 * The outcome of one save. [alreadyExisted] is what lets the UI tell "17 new
 * photos landed in your gallery" apart from "these were already there" --
 * without it, saving a photo twice and saving it once look identical.
 */
data class GallerySaveResult(val uri: Uri?, val alreadyExisted: Boolean)

/** Saves a photo into the device's shared gallery (visible in Photos/Gallery/Files, outside this app). */
interface PhotoGalleryRepository {
    /**
     * Idempotent: a photo already present under [displayName] is
     * left alone and reported with `alreadyExisted = true`, so re-running a
     * download can never pile up duplicate copies of the same photo.
     *
     * Returns the saved photo's content Uri when the platform provides one
     * (Android 10+, via MediaStore) -- null on older versions, where the
     * file is written directly into the public Pictures directory instead
     * and there's no equivalent Uri to hand back.
     * @throws Exception if the save fails for any reason (denied permission on API < 29, I/O error, ...).
     */
    suspend fun saveToGallery(photo: File, displayName: String): GallerySaveResult

    /**
     * Every gallery filename this app currently has in its Pictures folder.
     * Paired with each match's stored gallery name this answers "is this match still in the
     * user's gallery?" for a whole grid in one query, which is what keeps the
     * saved tick honest after someone deletes photos outside the app.
     *
     * Null means "couldn't tell" (no read access on API < 29) -- distinct
     * from an empty set, so a caller never mistakes "can't see the gallery"
     * for "the gallery is empty" and wrongly clears every tick.
     */
    suspend fun savedDisplayNames(): Set<String>?

}
