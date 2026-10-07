package com.example.snapfindai.presentation.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonSlot
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(uiState) {
        if (uiState is OnboardingUiState.Complete) onComplete()
    }
    OnboardingContent(
        uiState = uiState,
        onStart = { viewModel.startDownload() },
        onCancel = { viewModel.cancelDownload() },
        onPause = { viewModel.pauseDownload() },
    )
}

@Composable
private fun OnboardingContent(
    uiState: OnboardingUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit = {},
    onPause: () -> Unit = {},
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Hero(modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                Sheet(
                    modifier = Modifier.fillMaxWidth(),
                    uiState = uiState,
                    onStart = onStart,
                    onCancel = onCancel,
                    onPause = onPause,
                )
            }
        }
    }
}

@Composable
private fun Hero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Sheet(
    modifier: Modifier = Modifier,
    uiState: OnboardingUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(sheetShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = SnapFindSpacing.xl, vertical = SnapFindSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (uiState) {
            OnboardingUiState.ReadyToStart -> ReadyToStartContent(onStart = onStart)
            is OnboardingUiState.Downloading ->
                DownloadingContent(progress = uiState.progress, onCancel = onCancel, onPause = onPause)
            // Resume is onStart: continuing is the same call, because what
            // makes it a resume is the partial file, not a different request.
            is OnboardingUiState.Paused ->
                DownloadingContent(progress = uiState.progress, onCancel = onCancel, onResume = onStart)
            is OnboardingUiState.Error -> ErrorContent(message = uiState.message, onRetry = onStart)
            OnboardingUiState.Complete -> Unit // LaunchedEffect above navigates away
        }
    }
}

@Composable
private fun ReadyToStartContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Welcome to SnapFind AI",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            "SnapFind matches faces entirely on your device -- your photos never " +
                    "leave your phone. To do that, it needs to download its face-matching " +
                    "models once, about 16 MB. This needs an internet connection now; after " +
                    "that, it works fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SnapFindSpacing.sm),
        )
        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.lg)) {
            SnapFindButton(text = "Get Started", onClick = onStart)
        }
    }
}

/**
 * One composable for both running and paused: the screen is the same screen
 * with the transfer stopped, and splitting it in two would mean keeping two
 * copies of the progress ring in step by hand.
 *
 * Exactly one of [onPause]/[onResume] is given, which is what says which
 * state this is.
 */
@Composable
private fun DownloadingContent(
    progress: Float,
    onCancel: () -> Unit,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
) {
    val paused = onResume != null
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (paused) "Paused · ${(progress * 100).toInt()}%"
            else "Downloading · ${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (paused) "Resume when you're ready -- it picks up from here, " +
                "not from the beginning."
            else "Please wait until the on-device models finish downloading.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = SnapFindSpacing.xs,
                bottom = SnapFindSpacing.xs
            ),
        )
        WavyProgressRing(
            progress = progress,
            label = "${(progress * 100).toInt()}%",
            modifier = Modifier.padding(vertical = SnapFindSpacing.sm),
        )
        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.md)) {
            SnapFindButton(
                text = "Cancel",
                onClick = onCancel,
                variant = SnapFindButtonVariant.Tonal
            )
        }
        TransferToggleButton(
            paused = paused,
            onClick = onResume ?: onPause ?: {},
            modifier = Modifier
                .padding(top = SnapFindSpacing.md)
                .align(Alignment.End),
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
    SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.lg)) {
        SnapFindButton(text = "Retry", onClick = onRetry)
    }
}

/**
 * Pause while the download runs, resume while it's stopped -- one control in
 * one place, rather than two buttons swapping which is hidden.
 */
@Composable
private fun TransferToggleButton(paused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        elevation = FloatingActionButtonDefaults.elevation(SnapFindDimens.elevation),
    ) {
        Icon(
            if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (paused) "Resume download" else "Pause download",
        )
    }
}

@Preview(name = "Ready to start", showBackground = true)
@Composable
private fun OnboardingReadyToStartPreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.ReadyToStart, onStart = {}) }
}

@Preview(name = "Downloading", showBackground = true)
@Composable
private fun OnboardingDownloadingPreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.Downloading(0.67f), onStart = {}) }
}

@Preview(name = "Downloading - just started", showBackground = true)
@Composable
private fun OnboardingDownloadingStartPreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.Downloading(0.04f), onStart = {}) }
}

@Preview(name = "Paused", showBackground = true)
@Composable
private fun OnboardingPausedPreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.Paused(0.42f), onStart = {}) }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun OnboardingErrorPreview() {
    SnapFindAITheme {
        OnboardingContent(
            uiState = OnboardingUiState.Error("Couldn't download the face-matching models. Check your connection and try again."),
            onStart = {},
        )
    }
}

@Preview(name = "Ready to start - dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingReadyToStartDarkPreview() {
    SnapFindAITheme(darkTheme = true) { OnboardingContent(uiState = OnboardingUiState.ReadyToStart, onStart = {}) }
}

// Device-size coverage (doc section 9.1/9.3) -- the same state on a small
// and a large phone, to catch layout that only works on one screen size.
@Preview(name = "Small phone", showBackground = true, device = Devices.NEXUS_5)
@Composable
private fun OnboardingSmallPhonePreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.Downloading(0.5f), onStart = {}) }
}

@Preview(name = "Large phone", showBackground = true, device = Devices.PIXEL_7_PRO)
@Composable
private fun OnboardingLargePhonePreview() {
    SnapFindAITheme { OnboardingContent(uiState = OnboardingUiState.Downloading(0.5f), onStart = {}) }
}

