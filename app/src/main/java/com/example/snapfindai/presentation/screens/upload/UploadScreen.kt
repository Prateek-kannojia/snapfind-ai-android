package com.example.snapfindai.presentation.screens.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonSlot
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.SnapFindPhotoCard
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindSpacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadScreen(
    onNavigateToResults: () -> Unit,
    onOpenJob: (Long) -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val jobHistory by viewModel.jobHistory.collectAsState()
    val context = LocalContext.current

    var selfieUri by remember { mutableStateOf<Uri?>(null) }
    var zipUri by remember { mutableStateOf<Uri?>(null) }

    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> selfieUri = uri }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> zipUri = uri }

    // One-shot: fires exactly once per successful job, not derived from
    // uiState -- see UploadViewModel.navigateToResults for why.
    LaunchedEffect(Unit) {
        viewModel.navigateToResults.collect { onNavigateToResults() }
    }

    UploadContent(
        uiState = uiState,
        jobHistory = jobHistory,
        hasSelfie = selfieUri != null,
        hasZip = zipUri != null,
        onChooseSelfie = { selfieLauncher.launch("image/*") },
        onChooseZip = { zipLauncher.launch("application/zip") },
        onSubmit = {
            if (selfieUri != null && zipUri != null) {
                viewModel.submitJob(context, selfieUri!!, zipUri!!)
            }
        },
        onCancel = { viewModel.cancelJob() },
        onOpenJob = onOpenJob,
    )
}

@Composable
private fun UploadContent(
    uiState: UploadUiState,
    jobHistory: List<JobSummary>,
    hasSelfie: Boolean,
    hasZip: Boolean,
    onChooseSelfie: () -> Unit,
    onChooseZip: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onOpenJob: (Long) -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingValues ->
        val processingState = uiState as? UploadUiState.Processing
        if (processingState != null) {
            MatchingProgress(
                modifier = Modifier.padding(paddingValues),
                state = processingState,
                onCancel = onCancel,
            )
            return@Scaffold
        }

        // The whole form + Recent Jobs scrolls together as one screen,
        // rather than only the grid scrolling inside a weight(1f) region --
        // that's also why Recent Jobs below is a plain wrapping grid, not a
        // LazyVerticalGrid (which needs a separately bounded height to
        // coexist with an outer scroll container).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(SnapFindSpacing.screenHorizontal),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md)) {
                StepColumn(modifier = Modifier.weight(1f), label = "Step 1") {
                    SnapFindButton(
                        text = if (!hasSelfie) "Choose Selfie" else "Selected",
                        onClick = onChooseSelfie,
                        variant = SnapFindButtonVariant.Tonal,
                    )
                }
                StepColumn(modifier = Modifier.weight(1f), label = "Step 2") {
                    SnapFindButton(
                        text = if (!hasZip) "Choose ZIP" else "Selected",
                        onClick = onChooseZip,
                        variant = SnapFindButtonVariant.Outlined,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SnapFindSpacing.xl))

            SnapFindButton(text = "Find My Photos!", onClick = onSubmit, enabled = hasSelfie && hasZip)

            if (uiState is UploadUiState.Error) {
                Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
                Text(uiState.message, color = MaterialTheme.colorScheme.error)
            }

            if (jobHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(SnapFindSpacing.section))
                Text("Recent Jobs", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
                JobHistoryGrid(jobs = jobHistory, onOpenJob = onOpenJob)
            }
        }
    }
}

/** "Step N" label + its button, sharing one column so the two steps line up regardless of button label length. */
@Composable
private fun StepColumn(modifier: Modifier = Modifier, label: String, button: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(SnapFindSpacing.xs))
        button()
    }
}

/**
 * A plain wrapping 2-column grid, not LazyVerticalGrid -- Recent Jobs is
 * a short list, and a Lazy grid needs its own bounded height to coexist
 * with the outer scrollable Column, which just re-introduces the "reserve
 * a bunch of space whether or not there's content for it" problem this is
 * fixing. If Recent Jobs ever needs to show many dozens of jobs, that's
 * the point to reintroduce a properly height-constrained Lazy grid.
 */
@Composable
private fun JobHistoryGrid(jobs: List<JobSummary>, onOpenJob: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
        jobs.chunked(2).forEach { rowJobs ->
            Row(horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
                rowJobs.forEach { job ->
                    JobHistoryCard(modifier = Modifier.weight(1f), job = job, onClick = { onOpenJob(job.id) })
                }
                if (rowJobs.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Shown while FindFacesInPhotosUseCase is unzipping + scoring photos.
 * [state]'s scored/total are null until the first photo finishes scoring
 * (unzip + selfie embedding happen first) -- an indeterminate ring until
 * then, never a fabricated count.
 */
@Composable
private fun MatchingProgress(
    modifier: Modifier = Modifier,
    state: UploadUiState.Processing,
    onCancel: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SnapFindSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Flexible spacers, not a blanket Arrangement.Center over the whole
        // screen -- the content group centers as a unit within whatever
        // space is available, instead of the layout depending on exactly
        // how tall the device is.
        Spacer(modifier = Modifier.weight(1f))

        val fraction = if (state.scored != null && state.total != null && state.total > 0) {
            state.scored / state.total.toFloat()
        } else null

        WavyProgressRing(
            progress = fraction,
            label = fraction?.let { "${(it * 100).toInt()}%" },
            size = 128.dp,
        )
        Text(
            "Matching your face",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = SnapFindSpacing.lg),
        )
        Text(
            "Comparing your selfie against this batch, entirely on-device. This usually takes a few seconds.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SnapFindSpacing.sm),
        )
        if (state.scored != null && state.total != null) {
            Text(
                "${state.scored} of ${state.total} photos scanned",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SnapFindSpacing.md),
            )
        }

        // A real event folder takes minutes, so this screen can't be a
        // one-way door. Tonal rather than primary: stopping is the secondary
        // action here, the job itself is the point.
        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.xl)) {
            SnapFindButton(
                text = "Cancel",
                onClick = onCancel,
                variant = SnapFindButtonVariant.Tonal,
                fillWidth = false,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun JobHistoryCard(modifier: Modifier = Modifier, job: JobSummary, onClick: () -> Unit) {
    // Portrait-leaning, not square -- matches the approved design's Recent
    // Jobs card shape.
    SnapFindPhotoCard(modifier = modifier, aspectRatio = 0.85f, onClick = onClick) {
        if (job.previewPhoto != null) {
            AsyncImage(
                model = job.previewPhoto,
                contentDescription = "Job from ${formatJobTimestamp(job.timestamp)}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = SnapFindSpacing.sm, vertical = SnapFindSpacing.xs),
        ) {
            Text(
                "${job.matchCount} photo${if (job.matchCount == 1) "" else "s"}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                formatJobTimestamp(job.timestamp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatJobTimestamp(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))

// ---- Previews: Design > Split view in Android Studio, no build/install needed ----
// Job/photo previewPhoto Files are fake paths -- Coil can't load them in a
// preview (no real file I/O), so cards fall back to the placeholder icon.
// That's expected here, not a bug.

private val previewJobs = listOf(
    JobSummary(id = 1, timestamp = System.currentTimeMillis(), matchCount = 12, previewPhoto = File("preview.jpg")),
    JobSummary(id = 2, timestamp = System.currentTimeMillis() - 86_400_000, matchCount = 4, previewPhoto = null),
    JobSummary(id = 3, timestamp = System.currentTimeMillis() - 172_800_000, matchCount = 1, previewPhoto = null),
)

@Preview(name = "Idle - empty", showBackground = true)
@Composable
private fun UploadIdleEmptyPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = emptyList(),
            hasSelfie = false,
            hasZip = false,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - both picked, with history", showBackground = true)
@Composable
private fun UploadIdleReadyPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = previewJobs,
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Error, with history", showBackground = true)
@Composable
private fun UploadErrorPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Error("No matches found. Try a clearer selfie or a higher threshold."),
            jobHistory = previewJobs,
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Matching - indeterminate", showBackground = true)
@Composable
private fun UploadMatchingIndeterminatePreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Processing(),
            jobHistory = emptyList(),
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Matching - in progress", showBackground = true)
@Composable
private fun UploadMatchingProgressPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Processing(scored = 18, total = 24),
            jobHistory = emptyList(),
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UploadIdleDarkPreview() {
    SnapFindAITheme(darkTheme = true) {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = previewJobs,
            hasSelfie = false,
            hasZip = false,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Small phone", showBackground = true, device = Devices.NEXUS_5)
@Composable
private fun UploadSmallPhonePreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = previewJobs,
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Large phone", showBackground = true, device = Devices.PIXEL_7_PRO)
@Composable
private fun UploadLargePhonePreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = previewJobs,
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}
