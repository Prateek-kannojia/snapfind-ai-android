package com.example.snapfindai.presentation.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.domain.usecase.GetLastJobUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Independent of ResultsViewModel -- loads the same matches (same GetLastJobUseCase, same source of truth) but owns none of Results' selection/save state, since the viewer needs neither. */
@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    private val getLastJob: GetLastJobUseCase,
) : ViewModel() {

    private val _photos = MutableStateFlow<List<FaceMatchResult>>(emptyList())
    val photos: StateFlow<List<FaceMatchResult>> = _photos.asStateFlow()

    init {
        viewModelScope.launch {
            _photos.value = getLastJob()?.matches.orEmpty()
        }
    }
}
