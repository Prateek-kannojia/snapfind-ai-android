package com.quantumweavers.snapfindai.presentation.screens.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumweavers.snapfindai.domain.usecase.DownloadModelsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val downloadModels: DownloadModelsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ReadyToStart)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Fires once when the models are ready and the app should move on to the
     * upload screen.
     *
     * [OnboardingUiState.Complete] still exists, because the sheet has a
     * finished state to draw -- but navigation no longer comes from reading
     * it. A state that says "we are done" stays true after it has been acted
     * on, so anything observing it later acts on it again; an event is
     * consumed. A buffered channel so this can't be missed by a collector
     * that isn't attached yet, which for the one navigation that gets the
     * user out of onboarding is the only acceptable failure mode.
     */
    private val _navigateOnward = Channel<Unit>(Channel.BUFFERED)
    val navigateOnward: Flow<Unit> = _navigateOnward.receiveAsFlow()

    /**
     * The coroutine currently allowed to touch the partial download --
     * whether that's a transfer or the cleanup after a cancel.
     *
     * One slot, and every operation below joins whatever is in it before
     * starting its own work. The bytes already on disk *are* the download's
     * state, so two of these running at once is two writers on one temp file,
     * or a delete landing on a file a fresh download has just started
     * writing. Pause and cancel both stop their coroutine without waiting for
     * it, which is what made that reachable by tapping.
     *
     * The slot itself needs no synchronisation, on an invariant worth stating
     * since it is a plain `var` guarding a real resource: every function that
     * touches it ([startDownload], [pauseDownload], [cancelDownload]) is
     * called from the UI, and `viewModelScope` dispatches on `Main.immediate`,
     * so all reads and writes happen on the main thread. The hand-off is
     * ordered by that, not by the `Job` being thread-safe.
     */
    private var downloadJob: Job? = null

    /**
     * Starts the model download, or continues a paused one -- the same entry
     * point for both, because the difference lives in the partial file on
     * disk rather than in anything this has to remember.
     */
    fun startDownload() {
        if (_uiState.value is OnboardingUiState.Downloading) return
        // Resuming starts the bar where it stopped rather than at zero: the
        // first progress callback can be a whole file away (an
        // already-finished model reports nothing at all), and a bar that
        // jumps backwards to 0% reads as "it threw my download away".
        // Null, not 0f, on a fresh start: nothing has been downloaded and
        // nothing has been reported, which are different from "0% done" and
        // should look different too.
        val resumeFrom = (_uiState.value as? OnboardingUiState.Paused)?.progress
        // Claimed synchronously, before the coroutine starts, because the
        // body now begins by waiting on the previous attempt. Set inside the
        // launch -- as it used to be -- a second tap arriving during that
        // wait would still see Paused or ReadyToStart, pass the guard above,
        // and queue a second download behind the first.
        _uiState.value = OnboardingUiState.Downloading(resumeFrom)
        val previous = downloadJob
        downloadJob = viewModelScope.launch {
            // The fix for two separate races, both reachable by tapping:
            //
            // Resume. pauseDownload cancels without joining, so the paused
            // transfer can still be mid-write when this starts. Two writers,
            // one temp file.
            //
            // Cancel then Get Started. cancelDownload deletes the partial
            // file from its own coroutine. Without waiting for that to
            // finish, the delete lands *after* this download has started and
            // takes its fresh partial file with it -- the old comment there
            // reasoned about the stopped writer recreating the file, which is
            // a different hazard from a new writer losing its own.
            previous?.join()
            try {
                downloadModels { progress ->
                    // Only while still downloading: a cancel drops straight
                    // back to ReadyToStart, and a progress callback already in
                    // flight would otherwise flip the screen back to a
                    // progress bar with no download behind it.
                    if (_uiState.value is OnboardingUiState.Downloading) {
                        _uiState.value = OnboardingUiState.Downloading(progress)
                    }
                }
                _uiState.value = OnboardingUiState.Complete
                _navigateOnward.send(Unit)
            } catch (e: CancellationException) {
                // Rethrown rather than caught below: CancellationException
                // extends Exception, so the generic handler would report a
                // deliberate cancel to the user as a download failure.
                throw e
            } catch (e: Exception) {
                // Not e.message: what lands here is a socket error or a
                // checksum mismatch, both phrased for a developer. The user
                // gets the one sentence that's true of all of them, and the
                // actual cause goes to logcat.
                Log.w(TAG, "Model download failed", e)
                _uiState.value = OnboardingUiState.Error(
                    "Couldn't download the face-matching models. Check your connection and try again."
                )
            }
        }
    }

    /**
     * Stops the model download and returns to the start of onboarding. The
     * models are required for the app to do anything, so this isn't a way
     * past onboarding -- it's a way to stop ~16MB of traffic now and tap Get
     * Started again later.
     *
     * Safe to interrupt: ModelDownloader writes to a temp file and only
     * renames it once the checksum passes, so a cancelled download can never
     * leave a partial model behind for a later run to load.
     */
    fun cancelDownload() {
        val stopping = downloadJob
        _uiState.value = OnboardingUiState.ReadyToStart
        // The cleanup goes back into the slot rather than clearing it, so a
        // Get Started tapped immediately afterwards waits for the delete
        // instead of racing it. Clearing the slot was the bug: the next
        // download had nothing to wait on and could be underway by the time
        // discardPartialDownloads ran, which then deleted *its* partial file.
        downloadJob = viewModelScope.launch {
            // Joined before deleting, not just cancelled: cancel() only asks,
            // and deleting the file out from under a write that hasn't
            // stopped yet would let the writer recreate it.
            stopping?.cancelAndJoin()
            // Unlike a pause, this is the user saying they don't want it --
            // so the partial download goes too, rather than sitting in their
            // storage waiting for a retry that may never come.
            downloadModels.discardPartialDownloads()
        }
    }

    /**
     * Stops the transfer but keeps what it has, so Resume continues from
     * there instead of paying for the whole 16 MB again.
     *
     * The bytes already written are the state -- there's no in-memory
     * download to hold open -- which is why this can cancel the coroutine
     * outright and still be a pause rather than a restart.
     */
    fun pauseDownload() {
        // Matched on the state, not on the progress value: progress is null
        // while the request is still connecting, and `?: return` there would
        // quietly refuse to pause exactly when a user on a bad connection
        // most wants to.
        val downloading = _uiState.value as? OnboardingUiState.Downloading ?: return
        // Cancelled but deliberately left in the slot, where clearing it was
        // the bug: cancel() only asks the transfer to stop, so Resume has to
        // be able to wait for it to actually finish unwinding before it opens
        // the same temp file. There is nothing to join if the reference is
        // thrown away here.
        downloadJob?.cancel()
        _uiState.value = OnboardingUiState.Paused(downloading.progress)
    }

    private companion object {
        const val TAG = "OnboardingViewModel"
    }
}
