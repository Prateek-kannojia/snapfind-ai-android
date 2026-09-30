package com.example.snapfindai.presentation.screens.upload

import com.example.snapfindai.domain.model.FaceMatchResult

sealed interface UploadUiState {
    object Idle : UploadUiState
    /** [scored]/[total] are null until the first photo finishes scoring (unzip + selfie embedding happen first, with no per-photo count yet) -- an indeterminate progress ring until then, not a fabricated number. */
    data class Processing(val scored: Int? = null, val total: Int? = null) : UploadUiState
    data class Success(val matches: List<FaceMatchResult>) : UploadUiState
    data class Error(val message: String) : UploadUiState
}
