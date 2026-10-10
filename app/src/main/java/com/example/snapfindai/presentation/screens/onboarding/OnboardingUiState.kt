package com.example.snapfindai.presentation.screens.onboarding

sealed interface OnboardingUiState {
    data object ReadyToStart : OnboardingUiState
    /**
     * [progress] is null until the first bytes actually arrive -- a request
     * that is still connecting, or stalled on a bad connection, has no
     * fraction to report and a ring frozen at 0% reads as a hung app. Null
     * draws the indeterminate ring instead, which keeps moving and says the
     * honest thing: something is happening, we don't know how much yet.
     */
    data class Downloading(val progress: Float?) : OnboardingUiState

    /**
     * Stopped by the user with its bytes kept, so resuming continues from
     * [progress] instead of starting the ~16 MB again.
     *
     * Distinct from [ReadyToStart], which is where cancelling lands and where
     * the next attempt genuinely begins from nothing.
     */
    data class Paused(val progress: Float?) : OnboardingUiState
    data object Complete : OnboardingUiState
    data class Error(val message: String) : OnboardingUiState
}
