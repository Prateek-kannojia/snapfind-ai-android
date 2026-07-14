package com.example.snapfindai.presentation.screens.upload

import com.example.snapfindai.data.remote.MatchItem

sealed interface UploadUiState {
    object Idle : UploadUiState
    object Processing : UploadUiState
    data class Success(val matches: List<MatchItem>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
