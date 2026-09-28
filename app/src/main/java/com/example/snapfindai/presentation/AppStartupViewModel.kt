package com.example.snapfindai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.snapfindai.domain.usecase.CheckModelsReadyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Decides, once, whether the app can go straight to the Upload screen or
 * needs Onboarding first -- read by MainActivity's splash-screen
 * [android.window.SplashScreen.OnPreDrawListener] (via `keepOnScreenCondition`)
 * so that decision is made before the user sees any UI, not as a visible
 * screen flash after a blank first frame.
 */
@HiltViewModel
class AppStartupViewModel @Inject constructor(
    private val checkModelsReady: CheckModelsReadyUseCase,
) : ViewModel() {

    private val _isChecking = MutableStateFlow(true)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _modelsReady = MutableStateFlow(false)
    val modelsReady: StateFlow<Boolean> = _modelsReady.asStateFlow()

    init {
        viewModelScope.launch {
            _modelsReady.value = checkModelsReady()
            _isChecking.value = false
        }
    }
}
