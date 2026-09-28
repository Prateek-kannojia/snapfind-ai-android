package com.example.snapfindai.presentation.screens.onboarding

sealed interface OnboardingUiState {
    data object ReadyToStart : OnboardingUiState
    data class Downloading(val progress: Float) : OnboardingUiState
    data object Complete : OnboardingUiState
    data class Error(val message: String) : OnboardingUiState
}
