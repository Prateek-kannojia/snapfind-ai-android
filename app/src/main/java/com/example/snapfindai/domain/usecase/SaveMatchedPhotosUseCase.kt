package com.example.snapfindai.domain.usecase

import android.net.Uri
import android.util.Log
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.UserFacingException
import javax.inject.Inject

/** What a batch actually did, which is what the message afterwards has to be truthful about. */
data class BatchSaveOutcome(
    val savedCount: Int,
    /** Already in the gallery, so left alone. Saving is idempotent, and these are the proof of it. */
    val skippedCount: Int,
    val failedCount: Int,
    /** The most recently saved photo's gallery Uri, when the platform gives one -- the notification's tap target. */
    val lastSavedUri: Uri?,
)

/**
 * What happened to one photo on the way through a batch.
 *
 * One callback carrying these, rather than four separate ones, because
 * different screens care about different subsets: the results grid turns all
 * of them into a per-tile tick, while a screen that only shows an overall bar
 * ignores everything but [Progress]. Adding a case here doesn't force every
 * caller to grow a parameter.
 */
sealed interface PhotoSaveEvent {
    data class Started(val match: FaceMatchResult) : PhotoSaveEvent
    data class Saved(val match: FaceMatchResult) : PhotoSaveEvent
    /** [reason] is always safe to show -- see the message rule in [SaveMatchedPhotosUseCase]. */
    data class Failed(val match: FaceMatchResult, val reason: String) : PhotoSaveEvent
    data class Progress(val completed: Int, val total: Int) : PhotoSaveEvent
}

/**
 * Saves a list of matched photos to the gallery, one at a time, reporting as
 * it goes.
 *
 * Extracted from `ResultsViewModel` when a second screen needed it: the job
 * history grid can download whole jobs, and copying this loop would have been
 * two places to fix the next bug in it. What belongs to a screen -- a tick on
 * a tile, a progress bar, a snackbar -- stays with that screen, driven by
 * [PhotoSaveEvent]; what is the same wherever it runs lives here.
 *
 * **No filtering by save status.** Saving is idempotent, so an explicit
 * "download these" always does exactly what was asked, and a photo already in
 * the gallery costs a no-op instead of a duplicate. The user's selection is
 * never silently narrowed -- it is counted as [BatchSaveOutcome.skippedCount]
 * so the message afterwards can say what really changed.
 *
 * One photo's failure never ends the batch. A corrupt file or a MediaStore
 * refusal is about that photo, and stopping would strand the rest for no
 * reason.
 */
class SaveMatchedPhotosUseCase @Inject constructor(
    private val saveMatchedPhoto: SaveMatchedPhotoUseCase,
) {
    suspend operator fun invoke(
        targets: List<FaceMatchResult>,
        onEvent: (PhotoSaveEvent) -> Unit = {},
    ): BatchSaveOutcome {
        var savedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var lastSavedUri: Uri? = null

        targets.forEachIndexed { index, match ->
            onEvent(PhotoSaveEvent.Started(match))
            saveMatchedPhoto(match).fold(
                onSuccess = { outcome ->
                    if (outcome.alreadyExisted) skippedCount++ else savedCount++
                    lastSavedUri = outcome.uri ?: lastSavedUri
                    onEvent(PhotoSaveEvent.Saved(match))
                },
                onFailure = { error ->
                    failedCount++
                    // The same rule as everywhere else: a message written for
                    // a person is shown, an IOException's own text is logged
                    // and replaced. Here rather than in each caller, so two
                    // screens can't drift into saying different things about
                    // the same failure.
                    val reason = (error as? UserFacingException)?.message
                    if (reason == null) Log.w(TAG, "Couldn't save ${match.galleryName}", error)
                    onEvent(PhotoSaveEvent.Failed(match, reason ?: "Couldn't save this photo."))
                },
            )
            onEvent(PhotoSaveEvent.Progress(index + 1, targets.size))
        }

        return BatchSaveOutcome(savedCount, skippedCount, failedCount, lastSavedUri)
    }

    private companion object {
        const val TAG = "SaveMatchedPhotos"
    }
}
