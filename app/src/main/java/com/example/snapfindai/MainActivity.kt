package com.example.snapfindai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.snapfindai.ui.theme.SnapFindAITheme

import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.snapfindai.presentation.screens.upload.UploadScreen
import com.example.snapfindai.presentation.screens.upload.UploadViewModel
import com.example.snapfindai.presentation.screens.results.ResultsScreen
import com.example.snapfindai.presentation.screens.viewer.PhotoViewerScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnapFindAITheme {
                val navController = rememberNavController()
                val uploadViewModel: UploadViewModel = hiltViewModel()

                NavHost(navController = navController, startDestination = "upload") {
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
