package com.example.snapfindai.presentation.screens.upload

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.usecase.FindFacesInPhotosUseCase
import com.example.snapfindai.utils.FileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val findFacesInPhotos: FindFacesInPhotosUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    fun submitJob(context: Context, selfieUri: Uri, zipUri: Uri) {
        _uiState.value = UploadUiState.Processing

        viewModelScope.launch {
            // FileHelper does disk I/O (copying files), so we run it on the IO dispatcher
            val selfieFile = withContext(Dispatchers.IO) {
                FileHelper.uriToFile(context, selfieUri, "temp_selfie.jpg")
            }
            val zipFile = withContext(Dispatchers.IO) {
                FileHelper.uriToFile(context, zipUri, "temp_events.zip")
            }

            if (selfieFile == null || zipFile == null) {
                _uiState.value = UploadUiState.Error("Failed to read the selected files.")
                return@launch
            }

            findFacesInPhotos(selfieFile, zipFile).fold(
                onSuccess = { matches ->
                    _uiState.value = if (matches.isEmpty())
                        UploadUiState.Error("No matches found. Try a clearer selfie or a higher threshold.")
                    else
                        UploadUiState.Success(matches)
                },
                onFailure = { error ->
                    _uiState.value = UploadUiState.Error(error.message ?: "An unknown error occurred.")
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = UploadUiState.Idle
    }
}