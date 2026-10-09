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

    // Owned by the ViewModel, so a rotation doesn't discard what was picked
    // and ending a job can clear it -- see UploadViewModel.
    val selfieUri by viewModel.selfieUri.collectAsState()
    val zipUri by viewModel.zipUri.collectAsState()

    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onSelfiePicked(uri) }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onZipPicked(uri) }

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
        onSubmit = { viewModel.submitJob(context) },
        onCancel = { viewModel.cancelJob() },
        onOpenJob = onOpenJob,
        onDeleteJob = { viewModel.deleteJob(it) },
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
    onDeleteJob: (Long) -> Unit,
) {
    // Which card's delete is being confirmed, if any. Held here rather than
    // per card so only one dialog can ever be open.
    var jobPendingDeletion by remember { mutableStateOf<JobSummary?>(null) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent Jobs", style = MaterialTheme.typography.titleMedium)
                    // The cost of keeping these, stated where the user can see
                    // it. Each job holds a full-resolution copy of every photo
                    // it matched, and without a number on screen there is no
                    // reason to ever delete one -- the first place anybody
                    // would notice is the system settings screen.
                    Text(
                        formatBytes(jobHistory.sumOf { it.sizeBytes }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
                JobHistoryGrid(
                    jobs = jobHistory,
                    onOpenJob = onOpenJob,
                    onLongPressJob = { jobPendingDeletion = it },
                )
            }
        }
    }

    jobPendingDeletion?.let { job ->
        DeleteJobDialog(
            job = job,
            onConfirm = {
                jobPendingDeletion = null
                onDeleteJob(job.id)
            },
            onDismiss = { jobPendingDeletion = null },
        )
    }
}

/**
 * Spells out what a delete does and does not reach. The distinction is the
 * whole point: the app's own copies go, and anything the user downloaded is
 * theirs and stays in their gallery. Saying so here is what makes the action
 * safe to offer at all.
 */
@Composable
private fun DeleteJobDialog(job: JobSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this job?") },
        text = {
            Text(
                "This removes ${job.matchCount} matched photo${if (job.matchCount == 1) "" else "s"} " +
                    "from the app, along with the record of what matched, and frees " +
                    "${formatBytes(job.sizeBytes)}.\n\n" +
                    "Photos you've already saved to your gallery stay there."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rounded to whole units: this is a sense of scale, not an accounting figure. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 KB"
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
private fun JobHistoryGrid(
    jobs: List<JobSummary>,
    onOpenJob: (Long) -> Unit,
    onLongPressJob: (JobSummary) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
        jobs.chunked(2).forEach { rowJobs ->
            Row(horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
                rowJobs.forEach { job ->
                    JobHistoryCard(
                        modifier = Modifier.weight(1f),
                        job = job,
                        onClick = { onOpenJob(job.id) },
                        onLongClick = { onLongPressJob(job) },
                    )
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
private fun JobHistoryCard(
    modifier: Modifier = Modifier,
    job: JobSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Portrait-leaning, not square -- matches the approved design's Recent
    // Jobs card shape. Long-press to delete, the same gesture Results uses to
    // start selecting, rather than a delete affordance on every card.
    SnapFindPhotoCard(modifier = modifier, aspectRatio = 0.85f, onClick = onClick, onLongClick = onLongClick) {
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
    // Sizes are realistic rather than round: a matched photo is a
    // full-resolution copy, so a dozen of them is tens of megabytes. The
    // preview is where the total's formatting gets checked.
    JobSummary(id = 1, timestamp = System.currentTimeMillis(), matchCount = 12, previewPhoto = File("preview.jpg"), sizeBytes = 31_457_280),
    JobSummary(id = 2, timestamp = System.currentTimeMillis() - 86_400_000, matchCount = 4, previewPhoto = null, sizeBytes = 10_485_760),
    JobSummary(id = 3, timestamp = System.currentTimeMillis() - 172_800_000, matchCount = 1, previewPhoto = null, sizeBytes = 2_621_440),
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
        )
    }
}

@Preview(name = "Error, with history", showBackground = true)
@Composable
private fun UploadErrorPreview() {
    SnapFindAITheme {
        UploadContent(
            // Matches what the ViewModel actually emits. It used to offer "or
            // a higher threshold", which named a control the app has never
            // had -- a preview is where that kind of copy survives longest,
            // because nothing fails when it drifts from the real string.
            uiState = UploadUiState.Error("No matches found. Try a clearer selfie."),
            jobHistory = previewJobs,
            hasSelfie = true,
            hasZip = true,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
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
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {}, onDeleteJob = {},
        )
    }
}
