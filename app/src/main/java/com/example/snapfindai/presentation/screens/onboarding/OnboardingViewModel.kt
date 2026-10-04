package com.example.snapfindai.presentation.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.usecase.DownloadModelsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val downloadModels: DownloadModelsUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ReadyToStart)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    fun startDownload() {
        if (_uiState.value is OnboardingUiState.Downloading) return
        downloadJob = viewModelScope.launch {
            _uiState.value = OnboardingUiState.Downloading(0f)
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
            } catch (e: CancellationException) {
                // Rethrown rather than caught below: CancellationException
                // extends Exception, so the generic handler would report a
                // deliberate cancel to the user as a download failure.
                throw e
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error(
                    e.message ?: "Couldn't download the face-matching models. Check your connection and try again."
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
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = OnboardingUiState.ReadyToStart
    }
}
