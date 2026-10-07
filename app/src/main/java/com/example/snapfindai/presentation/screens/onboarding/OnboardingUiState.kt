package com.example.snapfindai.presentation.screens.onboarding

sealed interface OnboardingUiState {
    data object ReadyToStart : OnboardingUiState
    data class Downloading(val progress: Float) : OnboardingUiState

    /**
     * Stopped by the user with its bytes kept, so resuming continues from
     * [progress] instead of starting the ~16 MB again.
     *
     * Distinct from [ReadyToStart], which is where cancelling lands and where
     * the next attempt genuinely begins from nothing.
     */
    data class Paused(val progress: Float) : OnboardingUiState
    data object Complete : OnboardingUiState
    data class Error(val message: String) : OnboardingUiState
}
