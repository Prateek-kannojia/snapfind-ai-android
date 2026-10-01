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
        val result = photoGalleryRepository.saveToGallery(match.photo)
        val savedAt = System.currentTimeMillis()
        // Recorded even when the file was already there: the point of the
        // flag is what the gallery gained, while savedAt tracks what the
        // photo's state is, and both paths end with it present.
        jobHistoryRepository.markSavedToGallery(match.photo, savedAt)
        SaveOutcome(savedAt = savedAt, uri = result.uri, alreadyExisted = result.alreadyExisted)
    }
}
