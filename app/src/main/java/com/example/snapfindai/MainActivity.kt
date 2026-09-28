package com.example.snapfindai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.snapfindai.presentation.AppStartupViewModel
import com.example.snapfindai.ui.theme.SnapFindAITheme

import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.snapfindai.presentation.screens.onboarding.OnboardingScreen
import com.example.snapfindai.presentation.screens.upload.UploadScreen
import com.example.snapfindai.presentation.screens.upload.UploadViewModel
import com.example.snapfindai.presentation.screens.results.ResultsScreen
import com.example.snapfindai.presentation.screens.viewer.PhotoViewerScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val startupViewModel: AppStartupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keeps the system splash on screen until AppStartupViewModel knows
        // whether the models are already cached -- so the app never shows a
        // blank frame or an Onboarding-then-Upload flash while deciding.
        splashScreen.setKeepOnScreenCondition { startupViewModel.isChecking.value }

        enableEdgeToEdge()
        setContent {
            SnapFindAITheme {
                val isChecking by startupViewModel.isChecking.collectAsState()
                if (!isChecking) {
                    val modelsReady by startupViewModel.modelsReady.collectAsState()
                    val navController = rememberNavController()
                    val uploadViewModel: UploadViewModel = hiltViewModel()

                    NavHost(
                        navController = navController,
                        startDestination = if (modelsReady) "upload" else "onboarding",
                    ) {
                        composable("onboarding") {
                            OnboardingScreen(
                                onComplete = {
                                    navController.navigate("upload") {
                                        popUpTo("onboarding") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("upload") {
                            UploadScreen(
                                viewModel = uploadViewModel,
                                onNavigateToResults = { navController.navigate("results") }
                            )
                        }
                        composable("results") {
                            // ResultsScreen owns its own ViewModel (loads the
                            // last job itself) -- but going "back" still needs
                            // to reset uploadViewModel's state, or its
                            // Success-triggered LaunchedEffect would immediately
                            // navigate forward again. That coordination belongs
                            // here, not inside either screen.
                            ResultsScreen(
                                onNavigateBack = {
                                    uploadViewModel.resetState()
                                    navController.popBackStack()
                                },
                                onPhotoClick = { index -> navController.navigate("photo_viewer/$index") },
                            )
                        }
                        composable(
                            "photo_viewer/{startIndex}",
                            arguments = listOf(navArgument("startIndex") { type = NavType.IntType }),
                        ) { backStackEntry ->
                            PhotoViewerScreen(
                                startIndex = backStackEntry.arguments?.getInt("startIndex") ?: 0,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
