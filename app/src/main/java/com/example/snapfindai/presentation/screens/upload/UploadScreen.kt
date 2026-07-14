package com.example.snapfindai.presentation.screens.upload

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun UploadScreen(
    onNavigateToResults: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var selfieUri by remember { mutableStateOf<Uri?>(null) }
    var zipUri by remember { mutableStateOf<Uri?>(null) }

    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selfieUri = uri }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> zipUri = uri }

    // Navigation side-effect
    LaunchedEffect(uiState) {
        if (uiState is UploadUiState.Success) {
            onNavigateToResults()
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = uiState) {
                is UploadUiState.Processing -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Uploading and matching faces... This may take a while.", style = MaterialTheme.typography.bodyLarge)
                }
                else -> {
                    Text("Step 1: Select Your Selfie", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { 
                        Log.d("UploadScreen", "Selfie button clicked!")
                        selfieLauncher.launch("image/*") 
                    }) {
                        Text(if (selfieUri == null) "Choose Image" else "Image Selected")
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text("Step 2: Select Event Photos (ZIP)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { 
                        Log.d("UploadScreen", "ZIP button clicked!")
                        zipLauncher.launch("application/zip") 
                    }) {
                        Text(if (zipUri == null) "Choose ZIP Archive" else "ZIP Selected")
                    }

                    Spacer(modifier = Modifier.height(48.dp))

                    Button(
                        onClick = {
                            if (selfieUri != null && zipUri != null) {
                                viewModel.submitJob(context, selfieUri!!, zipUri!!)
                            }
                        },
                        enabled = selfieUri != null && zipUri != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Find My Photos!")
                    }

                    if (state is UploadUiState.Error) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
