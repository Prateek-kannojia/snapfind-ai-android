package com.example.snapfindai.presentation.screens.upload

import com.example.snapfindai.domain.model.FaceMatchResult

sealed interface UploadUiState {
    object Idle : UploadUiState
    object Processing : UploadUiState
    data class Success(val matches: List<FaceMatchResult>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
