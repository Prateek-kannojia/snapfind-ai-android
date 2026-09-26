package com.example.snapfindai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.snapfindai.ui.theme.SnapFindAITheme

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.snapfindai.presentation.screens.upload.UploadScreen
import com.example.snapfindai.presentation.screens.upload.UploadViewModel
import com.example.snapfindai.presentation.screens.results.ResultsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnapFindAITheme {
                val navController = rememberNavController()
                // Shared ViewModel for simplicity in this flow
                val sharedViewModel: UploadViewModel = hiltViewModel()

                NavHost(navController = navController, startDestination = "upload") {
                    composable("upload") {
                        UploadScreen(
                            viewModel = sharedViewModel,
                            onNavigateToResults = { navController.navigate("results") }
                        )
                    }
                    composable("results") {
                        ResultsScreen(
                            viewModel = sharedViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
