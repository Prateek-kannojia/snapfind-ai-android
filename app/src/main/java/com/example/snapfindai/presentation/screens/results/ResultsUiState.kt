package com.example.snapfindai.presentation.screens.results

import android.net.Uri
import com.example.snapfindai.domain.model.FaceMatchResult
import java.io.File

sealed interface ResultsUiState {
    object Loading : ResultsUiState
    data class Loaded(
        val jobId: Long,
        val matches: List<MatchItemState>,
        val selectedPhotos: Set<File> = emptySet(),
        /** Non-null while a batch (download-all / download-selected) save is running. */
        val batchProgress: BatchProgress? = null,
    ) : ResultsUiState {
        /** Derived, not stored -- deselecting the last photo automatically drops you back to normal browsing, same as Photos/Gmail/Drive, instead of leaving an empty-but-still-"selecting" state behind. */
        val selectionMode: Boolean get() = selectedPhotos.isNotEmpty()

        /**
         * What the bulk download button promises. Saving is idempotent, so
         * the button can act on every match regardless -- this number only
         * has to describe the outcome the user will actually see, which is
         * how many photos will newly land in their gallery.
         */
        val notSavedCount: Int get() = matches.count { it.saveStatus != SaveStatus.Saved }
    }
}

data class MatchItemState(val match: FaceMatchResult, val saveStatus: SaveStatus)

sealed interface SaveStatus {
    object Idle : SaveStatus
    object Saving : SaveStatus
    object Saved : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

data class BatchProgress(val completed: Int, val total: Int)

/** One-shot events the UI reacts to once (snackbar, notification) -- not part of uiState, so they don't re-fire on recomposition/config change. */
sealed interface ResultsEvent {
    /**
     * [skippedCount] were already in the gallery and were left alone, so the
     * message can stay truthful about what actually changed instead of
     * claiming to have saved photos that were already there.
     *
     * [lastSavedUri] is the most recently saved photo's gallery Uri, when
     * available -- used as the notification's tap target.
     */
    data class BatchSaveCompleted(
        val savedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val lastSavedUri: Uri?,
    ) : ResultsEvent
}
