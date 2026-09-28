package com.example.snapfindai.domain.usecase

import android.net.Uri
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import javax.inject.Inject

/** Saves one matched photo to the device gallery, then records that it was saved so the state survives leaving and returning to Results. */
class SaveMatchedPhotoUseCase @Inject constructor(
    private val photoGalleryRepository: PhotoGalleryRepository,
    private val jobHistoryRepository: JobHistoryRepository,
) {
    /** The pair's second value is the gallery content Uri, when the platform provides one (Android 10+) -- null on older versions. */
    suspend operator fun invoke(match: FaceMatchResult): Result<Pair<Long, Uri?>> = runCatching {
        val uri = photoGalleryRepository.saveToGallery(match.photo)
        val savedAt = System.currentTimeMillis()
        jobHistoryRepository.markSavedToGallery(match.photo, savedAt)
        savedAt to uri
    }
}
