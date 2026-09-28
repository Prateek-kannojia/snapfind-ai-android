package com.example.snapfindai.presentation.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Complete) onComplete()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Welcome to SnapFind AI",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "SnapFind matches faces entirely on your device -- your photos never " +
                    "leave your phone. To do that, it needs to download its face-matching " +
                    "models once, about 16 MB. This needs an internet connection now; after " +
                    "that, it works fully offline.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val state = uiState) {
                    OnboardingUiState.ReadyToStart -> {
                        Button(onClick = { viewModel.startDownload() }) {
                            Text("Get Started")
                        }
                    }
                    is OnboardingUiState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Downloading models... ${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is OnboardingUiState.Error -> {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { viewModel.startDownload() },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("Retry")
                        }
                    }
                    OnboardingUiState.Complete -> Unit // LaunchedEffect above navigates away
                }
            }
        }
    }
}
