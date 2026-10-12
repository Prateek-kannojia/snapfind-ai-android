package com.example.snapfindai.presentation.screens.viewer

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.model.UserFacingException
import com.example.snapfindai.domain.usecase.GetJobUseCase
import com.example.snapfindai.domain.usecase.ReconcileSavedPhotosUseCase
import com.example.snapfindai.domain.usecase.RemoveMatchedPhotosUseCase
import com.example.snapfindai.domain.usecase.SaveMatchedPhotoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

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
 *
 * Every suspending call below reaches Room, the filesystem or MediaStore, all
 * of which throw. Unhandled in a `viewModelScope` coroutine that is an
 * uncaught exception, which is a process death rather than an error message --
 * so each one states what it does when the read or write fails.
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

    // A Channel rather than a dropping SharedFlow, because this stream
    // carries [PhotoViewerEvent.Closed] alongside its messages. Losing a
    // message costs a snackbar; losing the close leaves the user looking at
    // a viewer with nothing in it and no way out but back.
    private val _events = Channel<PhotoViewerEvent>(Channel.BUFFERED)
    val events: Flow<PhotoViewerEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** Re-checks the gallery on resume, so a photo deleted elsewhere stops claiming to be saved. */
    fun refreshSavedState() {
        if (_savingPhoto.value != null) return
        viewModelScope.launch {
            // A failed reconcile leaves the ticks exactly as they are, rather
            // than reporting anything: what is on screen was correct a moment
            // ago, this runs on every resume, and the next one tries again.
            // Same rule as ResultsViewModel.refresh.
            val reconciled = try {
                reconcileSavedPhotos(_photos.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't re-check the gallery", e)
                return@launch
            }
            _photos.value = reconciled
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
                    _events.send(
                        PhotoViewerEvent.Message(
                            if (outcome.alreadyExisted) "Already in your gallery" else "Photo saved",
                        )
                    )
                },
                onFailure = { error ->
                    // The same rule as every other failure in the app: a
                    // message written for a person is shown, and anything else
                    // is logged and replaced. This was `error.message`, which
                    // can't tell the two apart -- so a MediaStore IOException
                    // put its own text ("EACCES (Permission denied)") straight
                    // into the snackbar. SaveMatchedPhotosUseCase does this for
                    // the batch path; the viewer saves one photo and so calls
                    // SaveMatchedPhotoUseCase directly, which is how it ended
                    // up bypassing it.
                    val reason = (error as? UserFacingException)?.message
                    if (reason == null) Log.w(TAG, "Couldn't save ${match.galleryName}", error)
                    _events.send(PhotoViewerEvent.Message(reason ?: "Couldn't save this photo."))
                },
            )
            _savingPhoto.value = null
        }
    }

    /** Curation only -- drops the photo from this job's matches and leaves any gallery copy untouched. */
    fun remove(match: FaceMatchResult) {
        viewModelScope.launch {
            try {
                removeMatchedPhotos(listOf(match))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reported and nothing else: the photo is still a match, so
                // the list stays as it is and the viewer stays open on it.
                // Removing it from the list anyway would show the user a
                // removal that didn't happen, and it would come back the next
                // time anything re-read the job.
                Log.w(TAG, "Couldn't remove ${match.photo.name}", e)
                _events.send(PhotoViewerEvent.Message("Couldn't remove this photo."))
                return@launch
            }
            _photos.value = _photos.value.filterNot { it.photo == match.photo }
            if (_photos.value.isEmpty()) _events.send(PhotoViewerEvent.Closed)
            else _events.send(PhotoViewerEvent.Message("Removed from matches"))
        }
    }

    private suspend fun load() {
        val loaded = try {
            reconcileSavedPhotos(getJob(jobId)?.matches.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Closed, rather than an error state. This screen has nowhere to
            // put one: an empty photo list renders a bare black box and
            // returns before the Scaffold -- and so before the snackbar host
            // -- is composed, so a message here would never be seen and the
            // user would be left on a black screen. Results is still intact
            // underneath, which makes going back both the honest outcome and
            // the only one they can act on.
            Log.w(TAG, "Couldn't load job $jobId", e)
            _events.send(PhotoViewerEvent.Closed)
            return
        }
        _photos.value = loaded
    }

    private companion object {
        const val TAG = "PhotoViewerViewModel"
    }
}
