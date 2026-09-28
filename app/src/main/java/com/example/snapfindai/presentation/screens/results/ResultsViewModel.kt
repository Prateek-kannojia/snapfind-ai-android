package com.example.snapfindai.presentation.screens.results

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.usecase.GetLastJobUseCase
import com.example.snapfindai.domain.usecase.RemoveMatchedPhotosUseCase
import com.example.snapfindai.domain.usecase.SaveMatchedPhotoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Owns Results-screen state independently of UploadViewModel -- loads the
 * last completed job itself (same GetLastJobUseCase UploadViewModel uses to
 * decide whether to resume it, but this is the copy actually rendered and
 * interacted with) and owns selection/save/remove state, which has no
 * reason to live on the upload flow's ViewModel.
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val getLastJob: GetLastJobUseCase,
    private val saveMatchedPhoto: SaveMatchedPhotoUseCase,
    private val removeMatchedPhotos: RemoveMatchedPhotosUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ResultsUiState>(ResultsUiState.Loading)
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ResultsEvent>()
    val events: SharedFlow<ResultsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val matches = getLastJob()?.matches.orEmpty()
            _uiState.value = ResultsUiState.Loaded(
                matches.map { MatchItemState(it, if (it.savedAt != null) SaveStatus.Saved else SaveStatus.Idle) }
            )
        }
    }

    // --- Selection mode ---

    fun toggleSelected(photo: File) = updateLoaded { state ->
        val newSelection = if (photo in state.selectedPhotos) state.selectedPhotos - photo else state.selectedPhotos + photo
        state.copy(selectionMode = true, selectedPhotos = newSelection)
    }

    fun clearSelection() = updateLoaded { it.copy(selectionMode = false, selectedPhotos = emptySet()) }

    // --- Remove ---

    fun removeSelected() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        val toRemove = state.matches.filter { it.match.photo in state.selectedPhotos }.map { it.match }
        if (toRemove.isEmpty()) return
        viewModelScope.launch {
            removeMatchedPhotos(toRemove)
            updateLoaded { s ->
                s.copy(
                    matches = s.matches.filterNot { it.match.photo in state.selectedPhotos },
                    selectionMode = false,
                    selectedPhotos = emptySet(),
                )
            }
        }
    }

    // --- Save (single / selected / all) ---

    fun saveToGallery(match: FaceMatchResult) = saveBatch(listOf(match))

    fun saveSelected() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        saveBatch(state.matches.filter { it.match.photo in state.selectedPhotos && it.saveStatus != SaveStatus.Saved }.map { it.match })
    }

    fun saveAll() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        saveBatch(state.matches.filter { it.saveStatus != SaveStatus.Saved }.map { it.match })
    }

    private fun saveBatch(targets: List<FaceMatchResult>) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            updateLoaded { it.copy(batchProgress = BatchProgress(0, targets.size)) }

            var savedCount = 0
            var failedCount = 0
            var lastSavedUri: Uri? = null

            targets.forEachIndexed { index, match ->
                updateStatus(match, SaveStatus.Saving)
                saveMatchedPhoto(match).fold(
                    onSuccess = { (_, uri) ->
                        savedCount++
                        lastSavedUri = uri ?: lastSavedUri
                        updateStatus(match, SaveStatus.Saved)
                    },
                    onFailure = { error ->
                        failedCount++
                        updateStatus(match, SaveStatus.Failed(error.message ?: "Couldn't save this photo."))
                    },
                )
                updateLoaded { it.copy(batchProgress = BatchProgress(index + 1, targets.size)) }
            }

            updateLoaded { it.copy(batchProgress = null, selectionMode = false, selectedPhotos = emptySet()) }
            _events.emit(ResultsEvent.BatchSaveCompleted(savedCount, failedCount, lastSavedUri))
        }
    }

    private fun updateStatus(match: FaceMatchResult, status: SaveStatus) = updateLoaded { state ->
        state.copy(matches = state.matches.map { if (it.match.photo == match.photo) it.copy(saveStatus = status) else it })
    }

    private inline fun updateLoaded(transform: (ResultsUiState.Loaded) -> ResultsUiState.Loaded) {
        val current = _uiState.value
        if (current is ResultsUiState.Loaded) _uiState.value = transform(current)
    }
}
