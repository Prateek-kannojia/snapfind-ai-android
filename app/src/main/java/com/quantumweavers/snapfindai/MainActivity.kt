package com.quantumweavers.snapfindai


import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quantumweavers.snapfindai.presentation.AppStartupViewModel
import com.quantumweavers.snapfindai.presentation.screens.onboarding.OnboardingScreen
import com.quantumweavers.snapfindai.presentation.screens.results.ResultsScreen
import com.quantumweavers.snapfindai.presentation.screens.upload.UploadScreen
import com.quantumweavers.snapfindai.presentation.screens.upload.UploadViewModel
import com.quantumweavers.snapfindai.presentation.screens.viewer.PhotoViewerScreen
import com.quantumweavers.snapfindai.ui.theme.SnapFindAITheme
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
                val isChecking by startupViewModel.isChecking.collectAsStateWithLifecycle()
                if (!isChecking) {
                    val modelsReady by startupViewModel.modelsReady.collectAsStateWithLifecycle()
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
                        ) { entry ->
                            // ResultsScreen owns its own ViewModel -- loads
                            // the jobId nav arg's job if present (opened from
                            // history), otherwise the last completed one
                            // (the just-finished-a-job flow).
                            //
                            // Nothing here refreshes the upload screen on the
                            // way out any more. That was a DisposableEffect
                            // calling uploadViewModel.resetState() from
                            // onDispose, chosen because disposal is the one
                            // thing the in-app back button, the system back
                            // gesture and the hardware key all have in
                            // common. They do -- but so do a rotation and a
                            // navigation *forward* into the photo viewer,
                            // neither of which is leaving, and both of which
                            // it therefore fired on. UploadScreen now asks on
                            // resume instead, which is when it actually needs
                            // the answer.
                            ResultsScreen(
                                onNavigateBack = { navController.popBackStack() },
                                // The photo's own path, not its position in the
                                // grid: the viewer loads the job itself, so a
                                // position would only land on the right photo
                                // as long as both lists happened to be built
                                // identically. Identity survives that.
                                onPhotoClick = entry.once { jobId: Long, photoPath: String ->
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

/**
 * Wraps a navigation action so it only runs while this destination is the
 * resumed one.
 *
 * A composed screen is hit-testable the instant it appears, but its back
 * stack entry is only marked `RESUMED` once the enter transition finishes.
 * Anything tapped in that window is a tap the user aimed at the screen they
 * were *leaving*. That produced a real and very confusing bug: tapping a job
 * card, coming back, and tapping it again quickly opened a photo instead of
 * the job -- the second tap landed on the results grid that had just appeared
 * underneath, where a matched photo occupies almost exactly the position the
 * job card did.
 *
 * Applied to this one callback and nothing else. Wrapping every navigation
 * made the app feel broken: a destination is only RESUMED once its *own*
 * transition has finished, so an ordinary tap on a job card landed in that
 * window and was swallowed, and the card needed two or three presses to
 * open. Only the results grid was ever receiving taps meant for the screen
 * behind it, so only the results grid guards against them.
 */
private fun <A, B> NavBackStackEntry.once(action: (A, B) -> Unit): (A, B) -> Unit = { a, b ->
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) action(a, b)
}
