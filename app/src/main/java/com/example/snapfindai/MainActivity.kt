package com.example.snapfindai

import android.net.Uri
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
import androidx.compose.runtime.DisposableEffect
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
                                onNavigateToResults = { navController.navigate("results") },
                                // Opening a past job from the history grid --
                                // separate from onNavigateToResults, which is
                                // only for the job just finished (no id: the
                                // newest one already is that job).
                                onOpenJob = { jobId -> navController.navigate("results?jobId=$jobId") },
                            )
                        }
                        composable(
                            "results?jobId={jobId}",
                            arguments = listOf(navArgument("jobId") { type = NavType.LongType; defaultValue = -1L }),
                        ) {
                            // ResultsScreen owns its own ViewModel -- loads
                            // the jobId nav arg's job if present (opened from
                            // history), otherwise the last completed one
                            // (the just-finished-a-job flow).
                            //
                            // Refreshing uploadViewModel on the way out (so
                            // its job-history grid picks up anything changed
                            // in Results, e.g. removed photos) has to run no
                            // matter HOW the user leaves this screen -- the
                            // in-app back button, the system back gesture, or
                            // the hardware back key all end up popping the
                            // stack, but only disposal is guaranteed to fire
                            // for all three. A callback wired to just the
                            // back button's onClick would miss the other two.
                            DisposableEffect(Unit) {
                                onDispose { uploadViewModel.resetState() }
                            }
                            ResultsScreen(
                                onNavigateBack = { navController.popBackStack() },
                                // The photo's own path, not its position in the
                                // grid: the viewer loads the job itself, so a
                                // position would only land on the right photo
                                // as long as both lists happened to be built
                                // identically. Identity survives that.
                                onPhotoClick = { jobId, photoPath ->
                                    navController.navigate("photo_viewer/$jobId?photo=${Uri.encode(photoPath)}")
                                },
                            )
                        }
                        composable(
                            "photo_viewer/{jobId}?photo={photo}",
                            arguments = listOf(
                                navArgument("jobId") { type = NavType.LongType },
                                // A query arg, not a path segment: an absolute
                                // file path contains the '/' that path segments
                                // are split on.
                                navArgument("photo") { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { backStackEntry ->
                            PhotoViewerScreen(
                                startPhotoPath = backStackEntry.arguments?.getString("photo"),
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
