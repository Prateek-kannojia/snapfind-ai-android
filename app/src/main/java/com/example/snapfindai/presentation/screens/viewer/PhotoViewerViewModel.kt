package com.example.snapfindai.presentation.screens.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.usecase.GetJobUseCase
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

/** One-shot feedback for an action taken on the photo currently on screen. */
sealed interface PhotoViewerEvent {
    data class Message(val text: String) : PhotoViewerEvent
    /** The last photo was removed, so there's nothing left to view. */
    object Closed : PhotoViewerEvent
}

/**
 * Independent of ResultsViewModel -- loads the same job (by the `jobId` nav
 * arg ResultsScreen passed through) but owns none of Results' selection
 * state. It does own save and remove: every action for a single photo lives
 * on the screen showing that photo, which is what keeps the grid free of
 * per-tile controls.
 */
@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getJob: GetJobUseCase,
    private val saveMatchedPhoto: SaveMatchedPhotoUseCase,
    private val removeMatchedPhotos: RemoveMatchedPhotosUseCase,
    private val reconcileSavedPhotos: ReconcileSavedPhotosUseCase,
) : ViewModel() {

    private val jobId: Long = checkNotNull(savedStateHandle["jobId"]) { "PhotoViewerScreen requires a jobId nav arg" }

    private val _photos = MutableStateFlow<List<FaceMatchResult>>(emptyList())
    val photos: StateFlow<List<FaceMatchResult>> = _photos.asStateFlow()

    /** The photo currently being written to the gallery, if any -- the viewer swaps its download action for a spinner. */
    private val _savingPhoto = MutableStateFlow<File?>(null)
    val savingPhoto: StateFlow<File?> = _savingPhoto.asStateFlow()

    private val _events = MutableSharedFlow<PhotoViewerEvent>()
    val events: SharedFlow<PhotoViewerEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Re-checks the gallery on resume, so a photo deleted elsewhere stops claiming to be saved. */
    fun refreshSavedState() {
        if (_savingPhoto.value != null) return
        viewModelScope.launch {
            _photos.value = reconcileSavedPhotos(_photos.value)
        }
    }

    fun save(match: FaceMatchResult) {
        if (_savingPhoto.value != null) return
        viewModelScope.launch {
            _savingPhoto.value = match.photo
            saveMatchedPhoto(match).fold(
                onSuccess = { outcome ->
                    _photos.value = _photos.value.map {
                        if (it.photo == match.photo) it.copy(savedAt = outcome.savedAt) else it
                    }
                    _events.emit(
                        PhotoViewerEvent.Message(
                            if (outcome.alreadyExisted) "Already in your gallery" else "Photo saved",
                        )
                    )
                },
                onFailure = { error ->
                    _events.emit(PhotoViewerEvent.Message(error.message ?: "Couldn't save this photo."))
                },
            )
            _savingPhoto.value = null
        }
    }

    /** Curation only -- drops the photo from this job's matches and leaves any gallery copy untouched. */
    fun remove(match: FaceMatchResult) {
        viewModelScope.launch {
            removeMatchedPhotos(listOf(match))
            _photos.value = _photos.value.filterNot { it.photo == match.photo }
            if (_photos.value.isEmpty()) _events.emit(PhotoViewerEvent.Closed)
            else _events.emit(PhotoViewerEvent.Message("Removed from matches"))
        }
    }

    private suspend fun load() {
        _photos.value = reconcileSavedPhotos(getJob(jobId)?.matches.orEmpty())
    }
}
