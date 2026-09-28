package com.example.snapfindai.presentation.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.usecase.DownloadModelsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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

    fun startDownload() {
        if (_uiState.value is OnboardingUiState.Downloading) return
        viewModelScope.launch {
            _uiState.value = OnboardingUiState.Downloading(0f)
            try {
                downloadModels { progress -> _uiState.value = OnboardingUiState.Downloading(progress) }
                _uiState.value = OnboardingUiState.Complete
            } catch (e: Exception) {
                _uiState.value = OnboardingUiState.Error(
                    e.message ?: "Couldn't download the face-matching models. Check your connection and try again."
                )
            }
        }
    }
}
