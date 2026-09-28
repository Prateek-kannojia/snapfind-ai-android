package com.example.snapfindai.presentation.screens.results

import android.net.Uri
import com.example.snapfindai.domain.model.FaceMatchResult
import java.io.File

sealed interface ResultsUiState {
    object Loading : ResultsUiState
    data class Loaded(
        val matches: List<MatchItemState>,
        val selectionMode: Boolean = false,
        val selectedPhotos: Set<File> = emptySet(),
        /** Non-null while a batch (download-all / download-selected) save is running. */
        val batchProgress: BatchProgress? = null,
    ) : ResultsUiState
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
    /** [lastSavedUri] is the most recently saved photo's gallery Uri, when available -- used as the notification's tap target. */
    data class BatchSaveCompleted(val savedCount: Int, val failedCount: Int, val lastSavedUri: Uri?) : ResultsEvent
}
