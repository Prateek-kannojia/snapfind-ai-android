package com.example.snapfindai.domain.usecase

import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.repository.JobHistoryRepository
import com.example.snapfindai.domain.repository.PhotoGalleryRepository
import java.io.File
import javax.inject.Inject

/**
 * Brings the stored "saved to gallery" flags back in line with what's
 * actually in the gallery, so the tick on a match means "this is in your
 * gallery right now" instead of "you tapped download at some point" -- the
 * user can delete these photos in Google Photos or Files at any time, and
 * nothing tells the app when they do.
 *
 * Both directions, in one pass over a single query: a photo that's gone
 * loses its tick, and a photo that's present but unflagged (app data
 * cleared, gallery kept) gets it back.
 */
class ReconcileSavedPhotosUseCase @Inject constructor(
    private val photoGalleryRepository: PhotoGalleryRepository,
    private val jobHistoryRepository: JobHistoryRepository,
) {
    suspend operator fun invoke(matches: List<FaceMatchResult>): List<FaceMatchResult> {
        // Null means the gallery couldn't be read at all (no permission on
        // API < 29). Leaving every flag untouched is the only safe response;
        // treating it as "nothing is saved" would wipe every tick.
        val presentInGallery = photoGalleryRepository.savedDisplayNames() ?: return matches

        val now = System.currentTimeMillis()
        val nowInGallery = mutableListOf<File>()
        val goneFromGallery = mutableListOf<File>()

        val reconciled = matches.map { match ->
            val inGallery = photoGalleryRepository.displayNameFor(match.photo) in presentInGallery
            when {
                inGallery && match.savedAt == null -> {
                    nowInGallery += match.photo
                    match.copy(savedAt = now)
                }
                !inGallery && match.savedAt != null -> {
                    goneFromGallery += match.photo
                    match.copy(savedAt = null)
                }
                else -> match
            }
        }

        jobHistoryRepository.clearSavedToGallery(goneFromGallery)
        nowInGallery.forEach { jobHistoryRepository.markSavedToGallery(it, now) }

        return reconciled
    }
}
