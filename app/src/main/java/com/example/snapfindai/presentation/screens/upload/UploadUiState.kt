package com.example.snapfindai.presentation.screens.upload

sealed interface UploadUiState {
    object Idle : UploadUiState
    /** [scored]/[total] are null until the first photo finishes scoring (unzip + selfie embedding happen first, with no per-photo count yet) -- an indeterminate progress ring until then, not a fabricated number. */
    data class Processing(val scored: Int? = null, val total: Int? = null) : UploadUiState

    /**
     * Carries no matches: the job now runs in a worker that outlives this
     * screen, and the results are read from the database by whoever displays
     * them. This state exists only as the moment navigation is triggered from
     * -- see UploadViewModel.navigateToResults.
     */
    object Success : UploadUiState
    data class Error(val message: String) : UploadUiState
}

/**
 * One-shot events the Upload screen reacts to once (snackbar, notification),
 * kept out of [UploadUiState] so they don't re-fire on recomposition.
 *
 * The same shape as the results screen's own event, because it reports the
 * same batch: see `SaveMatchedPhotosUseCase`, which both screens run.
 */
sealed interface UploadEvent {
    data class BatchSaveCompleted(
        val savedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val lastSavedUri: android.net.Uri?,
    ) : UploadEvent

    /**
     * [count] of the selected jobs couldn't be deleted.
     *
     * A delete that fails part-way used to say nothing at all: the selection
     * cleared, the grid refreshed, and the jobs that survived were simply
     * still there, which reads as the app having ignored the tap. The jobs
     * left on screen are the accurate outcome -- this is the sentence that
     * explains why they are.
     */
    data class JobsDeleteFailed(val count: Int) : UploadEvent
}
