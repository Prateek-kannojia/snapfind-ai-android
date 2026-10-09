package com.example.snapfindai.presentation.screens.results

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.usecase.GetJobUseCase
import com.example.snapfindai.domain.usecase.GetLastJobUseCase
import com.example.snapfindai.domain.usecase.ReconcileSavedPhotosUseCase
import com.example.snapfindai.domain.usecase.RemoveMatchedPhotosUseCase
import com.example.snapfindai.domain.usecase.PhotoSaveEvent
import com.example.snapfindai.domain.usecase.SaveMatchedPhotosUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val saveMatchedPhotos: SaveMatchedPhotosUseCase,
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
            // Reads the database and queries MediaStore, either of which can
            // throw. Unhandled that was an uncaught coroutine exception, so
            // opening Results on a bad read crashed the app instead of saying
            // anything.
            _uiState.value = try {
                val job = if (requestedJobId != null) getJob(requestedJobId) else getLastJob()
                val matches = reconcileSavedPhotos(job?.matches.orEmpty())
                ResultsUiState.Loaded(
                    jobId = job?.id ?: -1L,
                    matches = matches.map { MatchItemState(it, statusFor(it)) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The message is whatever Room or MediaStore threw -- a SQL
                // fragment or a content Uri -- so it's logged rather than
                // shown. There's nothing the user could do with it either way.
                Log.w(TAG, "Couldn't load matches", e)
                ResultsUiState.Error("Couldn't load your matches. Please try again.")
            }
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
            // Runs on every resume, and reconcile queries MediaStore -- the
            // most likely thing here to throw on an unusual device. A failed
            // refresh deliberately leaves the screen as it is rather than
            // replacing working content with an error: what's on screen was
            // correct a moment ago, and the next resume will try again.
            val job = runCatching {
                if (requestedJobId != null) getJob(requestedJobId) else getLastJob()
            }.getOrNull() ?: return@launch
            val matches = runCatching { reconcileSavedPhotos(job.matches) }.getOrNull() ?: return@launch

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
        if (state.operationInFlight) return
        val toRemove = state.matches.filter { it.match.photo in state.selectedPhotos }.map { it.match }
        if (toRemove.isEmpty()) return

        updateLoaded { it.copy(operationInFlight = true) }
        viewModelScope.launch {
            try {
                removeMatchedPhotos(toRemove)
                updateLoaded { s ->
                    s.copy(
                        matches = s.matches.filterNot { it.match.photo in state.selectedPhotos },
                        selectedPhotos = emptySet(),
                    )
                }
            } finally {
                // finally, so a failure can't leave the screen permanently
                // unable to save or remove anything.
                updateLoaded { it.copy(operationInFlight = false) }
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
        // Checked and claimed synchronously, before the coroutine starts.
        // These entry points are all called from the main thread, so the
        // check and the claim can't interleave -- which a second tap
        // arriving while a launch was still pending otherwise would.
        val state = _uiState.value as? ResultsUiState.Loaded ?: return
        if (state.operationInFlight) return
        updateLoaded { it.copy(operationInFlight = true, batchProgress = BatchProgress(0, targets.size)) }

        viewModelScope.launch {
            val outcome = try {
                // The loop itself is shared with the job-history grid, which
                // downloads whole jobs; what stays here is the part that is
                // about this screen -- a tick per tile and a progress bar.
                saveMatchedPhotos(targets) { event ->
                    when (event) {
                        is PhotoSaveEvent.Started -> updateStatus(event.match, SaveStatus.Saving)
                        is PhotoSaveEvent.Saved -> updateStatus(event.match, SaveStatus.Saved)
                        is PhotoSaveEvent.Failed -> updateStatus(event.match, SaveStatus.Failed(event.reason))
                        is PhotoSaveEvent.Progress ->
                            updateLoaded { it.copy(batchProgress = BatchProgress(event.completed, event.total)) }
                    }
                }
            } finally {
                // finally, so a failure can't leave the screen permanently
                // unable to save or remove anything.
                updateLoaded { it.copy(batchProgress = null, operationInFlight = false, selectedPhotos = emptySet()) }
            }

            _events.emit(
                ResultsEvent.BatchSaveCompleted(
                    savedCount = outcome.savedCount,
                    skippedCount = outcome.skippedCount,
                    failedCount = outcome.failedCount,
                    lastSavedUri = outcome.lastSavedUri,
                )
            )
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

    private companion object {
        const val TAG = "ResultsViewModel"
    }
}
