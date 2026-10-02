package com.example.snapfindai.presentation.screens.results

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.usecase.GetJobUseCase
import com.example.snapfindai.domain.usecase.GetLastJobUseCase
import com.example.snapfindai.domain.usecase.ReconcileSavedPhotosUseCase
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
 * Owns Results-screen state independently of UploadViewModel -- loads a job
 * itself and owns selection/save/remove state, which has no reason to live
 * on the upload flow's ViewModel. Which job: an explicit `jobId` nav arg
 * (opening a past job from the history grid) if present, otherwise the most
 * recently completed one (the normal "just finished a job" flow).
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getLastJob: GetLastJobUseCase,
    private val getJob: GetJobUseCase,
    private val saveMatchedPhoto: SaveMatchedPhotoUseCase,
    private val removeMatchedPhotos: RemoveMatchedPhotosUseCase,
    private val reconcileSavedPhotos: ReconcileSavedPhotosUseCase,
) : ViewModel() {

    private val requestedJobId: Long? = savedStateHandle.get<Long>("jobId")?.takeIf { it >= 0 }

    private val _uiState = MutableStateFlow<ResultsUiState>(ResultsUiState.Loading)
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ResultsEvent>()
    val events: SharedFlow<ResultsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            val job = if (requestedJobId != null) getJob(requestedJobId) else getLastJob()
            val matches = reconcileSavedPhotos(job?.matches.orEmpty())
            _uiState.value = ResultsUiState.Loaded(
                jobId = job?.id ?: -1L,
                matches = matches.map { MatchItemState(it, statusFor(it)) },
            )
        }
    }

    /**
     * Re-reads the job and re-checks the gallery. Called when the screen
     * comes back to the foreground, which is where both kinds of staleness
     * show up: deleting a photo from the gallery means leaving this app, and
     * the photo viewer can save or remove a match while it's on top of this
     * screen.
     */
    fun refresh() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        if (state.batchProgress != null) return // mid-batch: statuses are being written right now
        viewModelScope.launch {
            val job = if (requestedJobId != null) getJob(requestedJobId) else getLastJob()
            val matches = reconcileSavedPhotos(job?.matches.orEmpty())
            updateLoaded { current ->
                // A failure message is the user's only record of what went
                // wrong, so it survives a refresh; everything else comes
                // from what's now on disk and in the gallery.
                val failures = current.matches
                    .filter { it.saveStatus is SaveStatus.Failed }
                    .associate { it.match.photo to it.saveStatus }
                val stillPresent = matches.map { it.photo }.toSet()
                current.copy(
                    matches = matches.map { MatchItemState(it, failures[it.photo] ?: statusFor(it)) },
                    selectedPhotos = current.selectedPhotos intersect stillPresent,
                )
            }
        }
    }

    // --- Selection mode ---

    fun toggleSelected(photo: File) = updateLoaded { state ->
        val newSelection = if (photo in state.selectedPhotos) state.selectedPhotos - photo else state.selectedPhotos + photo
        state.copy(selectedPhotos = newSelection)
    }

    fun clearSelection() = updateLoaded { it.copy(selectedPhotos = emptySet()) }

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
                    selectedPhotos = emptySet(),
                )
            }
        }
    }

    // --- Save (single / selected / all) ---

    // No filtering by save status anywhere below: saving is idempotent, so
    // an explicit "download these" always does exactly what was asked and a
    // photo that's already in the gallery costs a no-op instead of a
    // duplicate. The user's selection is never silently narrowed.

    fun saveToGallery(match: FaceMatchResult) = saveBatch(listOf(match))

    fun saveSelected() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        saveBatch(state.matches.filter { it.match.photo in state.selectedPhotos }.map { it.match })
    }

    fun saveAll() {
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        saveBatch(state.matches.map { it.match })
    }

    private fun saveBatch(targets: List<FaceMatchResult>) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            updateLoaded { it.copy(batchProgress = BatchProgress(0, targets.size)) }

            var savedCount = 0
            var skippedCount = 0
            var failedCount = 0
            var lastSavedUri: Uri? = null

            targets.forEachIndexed { index, match ->
                updateStatus(match, SaveStatus.Saving)
                saveMatchedPhoto(match).fold(
                    onSuccess = { outcome ->
                        if (outcome.alreadyExisted) skippedCount++ else savedCount++
                        lastSavedUri = outcome.uri ?: lastSavedUri
                        updateStatus(match, SaveStatus.Saved)
                    },
                    onFailure = { error ->
                        failedCount++
                        updateStatus(match, SaveStatus.Failed(error.message ?: "Couldn't save this photo."))
                    },
                )
                updateLoaded { it.copy(batchProgress = BatchProgress(index + 1, targets.size)) }
            }

            updateLoaded { it.copy(batchProgress = null, selectedPhotos = emptySet()) }
            _events.emit(ResultsEvent.BatchSaveCompleted(savedCount, skippedCount, failedCount, lastSavedUri))
        }
    }

    private fun statusFor(match: FaceMatchResult) =
        if (match.savedAt != null) SaveStatus.Saved else SaveStatus.Idle

    private fun updateStatus(match: FaceMatchResult, status: SaveStatus) = updateLoaded { state ->
        state.copy(matches = state.matches.map { if (it.match.photo == match.photo) it.copy(saveStatus = status) else it })
    }

    private inline fun updateLoaded(transform: (ResultsUiState.Loaded) -> ResultsUiState.Loaded) {
        val current = _uiState.value
        if (current is ResultsUiState.Loaded) _uiState.value = transform(current)
    }
}
