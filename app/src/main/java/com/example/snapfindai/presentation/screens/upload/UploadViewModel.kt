package com.example.snapfindai.presentation.screens.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.domain.usecase.FindFacesInPhotosUseCase
import com.example.snapfindai.domain.usecase.GetJobHistoryUseCase
import com.example.snapfindai.utils.FileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// ViewModel's only jobs:
//   1. Convert Android types (Uri, Context) to plain types (File) — bridges Android world to domain world
//   2. Call the use case
//   3. Update UI state based on the result
//
// No API calls, no polling, no business decisions here.
@HiltViewModel
class UploadViewModel @Inject constructor(
    private val findFacesInPhotos: FindFacesInPhotosUseCase,
    private val getJobHistory: GetJobHistoryUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    // Every past job, newest first, shown as grid cards on this screen --
    // opening one navigates straight to its Results, bypassing uiState
    // entirely (see MainActivity's onOpenJob wiring).
    private val _jobHistory = MutableStateFlow<List<JobSummary>>(emptyList())
    val jobHistory: StateFlow<List<JobSummary>> = _jobHistory.asStateFlow()

    // One-shot: navigating off of `uiState == Success` (as UploadScreen used
    // to) breaks the moment Results is left via the system back gesture
    // instead of its in-app back button -- that path pops straight through
    // Compose Navigation's own back handler, never touching resetState(), so
    // uiState is still Success when Upload reappears and immediately
    // re-navigates forward. A one-shot event fires exactly once, at the
    // moment a job actually succeeds, and is never re-derived from state
    // afterward -- so it can't misfire on re-entry no matter how the user
    // got back here.
    private val _navigateToResults = MutableSharedFlow<Unit>()
    val navigateToResults: SharedFlow<Unit> = _navigateToResults.asSharedFlow()

    // Held so the user can call the job off -- a real event folder takes
    // minutes, which is long enough that committing to it by one tap with no
    // way out isn't reasonable.
    private var matchingJob: Job? = null

    init {
        refreshHistory()
    }

    /**
     * Stops the running job and goes back to the picker. Cancellation is
     * cooperative, so this returns before the job has necessarily noticed --
     * which is why the state is set here rather than waiting for the
     * coroutine to unwind, and why [UploadUiState.Processing] is checked
     * before each progress update below.
     *
     * The use case's own `finally` still runs on cancellation, so the
     * half-extracted event folder is cleaned up rather than abandoned.
     */
    fun cancelJob() {
        matchingJob?.cancel()
        matchingJob = null
        _uiState.value = UploadUiState.Idle
    }

    fun submitJob(context: Context, selfieUri: Uri, zipUri: Uri) {
        _uiState.value = UploadUiState.Processing()

        matchingJob = viewModelScope.launch {
            // FileHelper does disk I/O (copying files), so we run it on the IO dispatcher
            val selfieFile = withContext(Dispatchers.IO) {
                // Reclaims anything a previous run left behind by being killed
                // mid-job. These files live in filesDir now, so unlike cacheDir
                // nothing else will ever clear them.
                FileHelper.prepareJobWorkDir(context)
                FileHelper.uriToFile(context, selfieUri, "temp_selfie.jpg")
            }
            val zipFile = withContext(Dispatchers.IO) {
                FileHelper.uriToFile(context, zipUri, "temp_events.zip")
            }

            if (selfieFile == null || zipFile == null) {
                _uiState.value = UploadUiState.Error("Failed to read the selected files.")
                return@launch
            }

            findFacesInPhotos(selfieFile, zipFile, onProgress = { scored, total ->
                // Only while still processing: a cancel sets Idle immediately,
                // and a progress callback already in flight would otherwise
                // flip the screen back to Processing with no job behind it.
                if (_uiState.value is UploadUiState.Processing) {
                    _uiState.value = UploadUiState.Processing(scored, total)
                }
            }).fold(
                onSuccess = { matches ->
                    if (matches.isEmpty()) {
                        _uiState.value = UploadUiState.Error("No matches found. Try a clearer selfie or a higher threshold.")
                    } else {
                        _uiState.value = UploadUiState.Success(matches)
                        refreshHistory() // the job that was just completed now shows up as a card too
                        _navigateToResults.emit(Unit)
                    }
                },
                onFailure = { error ->
                    _uiState.value = UploadUiState.Error(error.message ?: "An unknown error occurred.")
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = UploadUiState.Idle
        refreshHistory() // picks up newly-saved/removed jobs when returning from Results
    }

    private fun refreshHistory() {
        viewModelScope.launch { _jobHistory.value = getJobHistory() }
    }
}
