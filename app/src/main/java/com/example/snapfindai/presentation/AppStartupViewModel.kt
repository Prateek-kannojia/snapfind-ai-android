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
            // The check reads files and hashes them, so it can fail on I/O.
            // Unhandled, that was an uncaught coroutine exception -- a crash
            // on launch, before any UI exists to report it.
            //
            // "Couldn't tell" degrades to "not ready", which routes to
            // Onboarding. That screen will try to fetch the models and has
            // its own error state and Retry, so a real problem surfaces
            // somewhere the user can act on it instead of at the splash.
            _modelsReady.value = runCatching { checkModelsReady() }.getOrDefault(false)

            // finally in spirit: the splash is held up by this flag, so
            // failing to clear it would leave the app on the splash screen
            // forever with no way forward.
            _isChecking.value = false
        }
    }
}
