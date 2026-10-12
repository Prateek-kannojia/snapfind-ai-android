package com.example.snapfindai.presentation.screens.onboarding

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonSlot
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.SnapFindMark
import com.example.snapfindai.presentation.components.SnapFindMarkState
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.ui.theme.ClaySheetShape
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // A one-shot event, not a state this re-derives. `LaunchedEffect(uiState)
    // { if (uiState is Complete) onComplete() }` navigates off a value that
    // stays true after the navigation, so it re-fires for any observer that
    // sees Complete again -- the same shape of bug UploadViewModel documents
    // for its own navigateToResults, where it broke in practice. Here
    // popUpTo(inclusive = true) happens to pop this destination and hide it,
    // which makes it latent rather than visible; it is not a reason to keep
    // the pattern on the one screen that still used it.
    LaunchedEffect(Unit) {
        viewModel.navigateOnward.collect { onComplete() }
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val sheetMinHeight = maxHeight * SnapFindDimens.sheetMinHeightFraction
            Column(modifier = Modifier.fillMaxSize()) {
                Hero(modifier = Modifier.weight(1f).fillMaxWidth())
                Sheet(
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = sheetMinHeight,
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
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnapFindMark(state = SnapFindMarkState.Active)
        Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
        Text(
            "One-time setup",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One state of the sheet: drawn when it is the current one, and otherwise
 * measured and thrown away so it still contributes its height to the parent.
 *
 * [state] is taken only so Compose re-measures when it changes; the content
 * decides for itself what to show.
 */
@Composable
private fun SheetState(
    state: OnboardingUiState,
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                if (visible) placeable.placeRelative(0, 0)
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        content()
    }
}

@Composable
private fun Sheet(
    modifier: Modifier = Modifier,
    minHeight: Dp,
    uiState: OnboardingUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
) {
    val sheetShape = ClaySheetShape
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .shadow(elevation = SnapFindDimens.elevation, shape = sheetShape, clip = false)
            .border(SnapFindDimens.borderWidth, MaterialTheme.colorScheme.outline, sheetShape)
            .clip(sheetShape)
            .background(MaterialTheme.colorScheme.surface)
            .animateContentSize()
            .padding(horizontal = SnapFindSpacing.xl, vertical = SnapFindSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            SheetState(uiState, visible = uiState is OnboardingUiState.ReadyToStart) {
                ReadyToStartContent(onStart = onStart)
            }
            SheetState(uiState, visible = uiState is OnboardingUiState.Downloading) {
                DownloadingContent(
                    progress = (uiState as? OnboardingUiState.Downloading)?.progress,
                    onCancel = onCancel,
                    onPause = onPause,
                )
            }
            // Resume is onStart: continuing is the same call, because what
            // makes it a resume is the partial file, not a different request.
            SheetState(uiState, visible = uiState is OnboardingUiState.Paused) {
                DownloadingContent(
                    progress = (uiState as? OnboardingUiState.Paused)?.progress,
                    onCancel = onCancel,
                    onResume = onStart,
                )
            }
            SheetState(uiState, visible = uiState is OnboardingUiState.Error) {
                ErrorContent(
                    message = (uiState as? OnboardingUiState.Error)?.message.orEmpty(),
                    onRetry = onStart,
                )
            }
            // Complete places nothing: the LaunchedEffect above navigates away.
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
            text = "Your photos never leave this phone",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            "SnapFind matches faces entirely on your device. " +
                "To do that it needs to download its matching models once, about 16 MB. " +
                "After that it works fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = SnapFindSpacing.sm),
        )
        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.lg)) {
            SnapFindButton(text = "Download models · 16 MB", onClick = onStart)
        }
        Text(
            "Takes about a minute on Wi-Fi",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SnapFindSpacing.xs),
        )
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
    progress: Float?,
    onCancel: () -> Unit,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
) {
    val paused = onResume != null
    // Null progress means nothing has been reported yet -- still connecting,
    // or a connection slow enough that no bytes have landed. There is no
    // honest percentage to show, so none is shown: the heading drops the
    // number and the ring goes indeterminate, which keeps moving. A ring
    // sitting at a motionless 0% was the thing that read as a hung app.
    val percent = progress?.let { "${(it * 100).toInt()}%" }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                paused && percent != null -> "Paused · $percent"
                paused -> "Paused"
                percent != null -> "Downloading · $percent"
                else -> "Connecting…"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                paused -> "Resume when you're ready."
                percent != null -> "Please wait until the on-device models finish downloading."
                else -> "Reaching the server. This needs a connection the first time only."
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(
                top = SnapFindSpacing.xs,
                bottom = SnapFindSpacing.xs,
            ),
        )
        WavyProgressRing(
            progress = progress,
            label = percent,
            modifier = Modifier.padding(vertical = SnapFindSpacing.sm),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SnapFindSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.sm),
        ) {
            SnapFindButton(
                text = if (paused) "Resume" else "Pause",
                onClick = onResume ?: onPause ?: {},
                variant = SnapFindButtonVariant.Tonal,
                modifier = Modifier.weight(1f),
            )
            SnapFindButton(
                text = "Cancel",
                onClick = onCancel,
                variant = SnapFindButtonVariant.Tonal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    // A Column, like every other state here. Without one this emitted two
    // siblings into the sheet's Box, which stacks rather than lists -- so the
    // message and the Retry button drew on top of each other and the padding
    // between them had nothing to push against.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.lg)) {
            SnapFindButton(text = "Retry", onClick = onRetry)
        }
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

