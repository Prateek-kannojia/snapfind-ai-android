package com.example.snapfindai.domain.usecase

import android.net.Uri
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import javax.inject.Inject

/**
 * The photo is in the gallery either way; [alreadyExisted] says whether this
 * call is what put it there. Only the UI cares -- it's the difference between
 * reporting "17 saved" and "17 saved, 7 already in gallery".
 *
 * [uri] is the gallery content Uri, when the platform provides one (Android
 * 10+) -- null on older versions.
 */
data class SaveOutcome(val savedAt: Long, val uri: Uri?, val alreadyExisted: Boolean)

/** Saves one matched photo to the device gallery, then records that it was saved so the state survives leaving and returning to Results. */
class SaveMatchedPhotoUseCase @Inject constructor(
    private val photoGalleryRepository: PhotoGalleryRepository,
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(match: FaceMatchResult): Result<SaveOutcome> = runCatching {
        // Asserted rather than defaulted: anything the user can reach a save
        // action for came out of storage, where the name is always present.
        // Inventing one here instead would produce a name that doesn't match
        // the photo's own, quietly breaking the "already saved" check.
        val displayName = requireNotNull(match.galleryName) {
            "A match can only be saved once it has been persisted and named"
        }
        val result = photoGalleryRepository.saveToGallery(match.photo, displayName)
        val savedAt = System.currentTimeMillis()
        // Recorded even when the file was already there: the point of the
        // flag is what the gallery gained, while savedAt tracks what the
        // photo's state is, and both paths end with it present.
        jobHistoryRepository.markSavedToGallery(match.photo, savedAt)
        SaveOutcome(savedAt = savedAt, uri = result.uri, alreadyExisted = result.alreadyExisted)
    }
}
