package com.example.snapfindai.presentation.screens.upload

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import com.example.snapfindai.presentation.components.SnapFindSelectionIndicator
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.utils.DownloadNotificationHelper
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindSpacing
import java.io.File

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

    val selectedJobs by viewModel.selectedJobs.collectAsState()
    val operationInFlight by viewModel.operationInFlight.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveToGallery = rememberGallerySaveGate()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UploadEvent.BatchSaveCompleted -> {
                    // Worded exactly as the results screen words it: saving is
                    // idempotent, so "nothing new landed" is a normal outcome
                    // and must not read as a failure.
                    val message = when {
                        event.failedCount > 0 && event.savedCount == 0 -> "Couldn't save ${event.failedCount} photo(s)"
                        event.failedCount > 0 -> "${event.savedCount} saved, ${event.failedCount} failed"
                        event.savedCount == 0 -> "Already in your gallery"
                        event.skippedCount > 0 -> "${event.savedCount} saved, ${event.skippedCount} already in gallery"
                        else -> "${event.savedCount} photo(s) saved"
                    }
                    snackbarHostState.showSnackbar(message)
                    if (event.savedCount > 0 || event.failedCount > 0) {
                        DownloadNotificationHelper.showCompleted(
                            context, event.savedCount, event.failedCount, event.lastSavedUri,
                        )
                    }
                }
            }
        }
    }

    UploadContent(
        uiState = uiState,
        jobHistory = jobHistory,
        hasSelfie = selfieUri != null,
        hasZip = zipUri != null,
        selectedJobs = selectedJobs,
        operationInFlight = operationInFlight,
        snackbarHostState = snackbarHostState,
        onChooseSelfie = { selfieLauncher.launch("image/*") },
        onChooseZip = { zipLauncher.launch("application/zip") },
        onSubmit = { viewModel.submitJob(context) },
        onCancel = { viewModel.cancelJob() },
        onOpenJob = onOpenJob,
        onDeleteJobs = { viewModel.deleteSelectedJobs() },
        onDownloadJobs = { saveToGallery { viewModel.downloadSelectedJobs() } },
        onToggleJobSelected = { viewModel.toggleJobSelected(it) },
        onClearSelection = { viewModel.clearSelection() },
    )
}

@Composable
private fun UploadContent(
    uiState: UploadUiState,
    jobHistory: List<JobSummary>,
    hasSelfie: Boolean,
    hasZip: Boolean,
    selectedJobs: Set<Long> = emptySet(),
    operationInFlight: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onChooseSelfie: () -> Unit,
    onChooseZip: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onOpenJob: (Long) -> Unit,
    onDeleteJobs: () -> Unit = {},
    onDownloadJobs: () -> Unit = {},
    onToggleJobSelected: (Long) -> Unit = {},
    onClearSelection: () -> Unit = {},
) {
    // Set when the delete is confirmed rather than per card, so only one
    // dialog can ever be open and it always describes the whole selection.
    var confirmingDelete by remember { mutableStateOf(false) }
    val selectionMode = selectedJobs.isNotEmpty()

    // Disabled (and so inert) once nothing is selected, letting back behave
    // normally again -- the same rule the results grid uses.
    BackHandler(enabled = selectionMode) { onClearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            UploadHeader(
                selectionMode = selectionMode,
                selectedCount = selectedJobs.size,
                operationInFlight = operationInFlight,
                onClearSelection = onClearSelection,
                onDownloadSelected = onDownloadJobs,
                onDeleteSelected = { confirmingDelete = true },
            )
        },
    ) { paddingValues ->
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
                    selectedJobs = selectedJobs,
                    selectionMode = selectionMode,
                    onOpenJob = onOpenJob,
                    onToggleSelected = onToggleJobSelected,
                )
            }
        }
    }

    if (confirmingDelete) {
        val selected = jobHistory.filter { it.id in selectedJobs }
        DeleteJobsDialog(
            jobs = selected,
            onConfirm = {
                confirmingDelete = false
                onDeleteJobs()
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/**
 * States the whole cost before the tap, and the one guarantee that makes it
 * tappable: the gallery is untouched. That is structural rather than careful
 * -- `deleteJob` addresses this app's own storage and names no MediaStore
 * path at all -- which is why it can be promised here.
 */
@Composable
private fun DeleteJobsDialog(jobs: List<JobSummary>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val photoCount = jobs.sumOf { it.matchCount }
    val freed = formatBytes(jobs.sumOf { it.sizeBytes })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (jobs.size == 1) "Delete this job?" else "Delete ${jobs.size} jobs?") },
        text = {
            Text(
                "This removes $photoCount matched photo${if (photoCount == 1) "" else "s"} " +
                    "from the app, along with the record of what matched, and frees $freed.\n\n" +
                    "Photos you've already saved to your gallery stay there."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The same two-bar arrangement as the results grid, for the same reason:
 * selection *replaces* the default bar rather than adding to it, so no two
 * sets of actions are ever on screen together.
 */
@Composable
private fun UploadHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    operationInFlight: Boolean,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    AnimatedContent(
        targetState = selectionMode,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "upload-app-bar",
    ) { selecting ->
        if (selecting) {
            UploadSelectionTopAppBar(
                selectedCount = selectedCount,
                operationInFlight = operationInFlight,
                onClearSelection = onClearSelection,
                onDownloadSelected = onDownloadSelected,
                onDeleteSelected = onDeleteSelected,
            )
        } else {
            UploadDefaultTopAppBar()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadDefaultTopAppBar() {
    // No navigation icon: this is the root screen, and a back arrow here
    // would offer to leave the app. No actions either -- there is no
    // "download everything" that means anything across unrelated jobs.
    TopAppBar(title = { Text("SnapFind AI") })
}

/**
 * Close clears the selection, exactly as the results grid does, so the
 * gesture to get out of selecting is the same on both screens.
 *
 * The download label stays the bare verb because the title already carries
 * the count. Both actions are disabled together while anything runs: they
 * touch the same files, and the dangerous combination isn't two downloads --
 * it's a delete landing while photos are mid-copy into the gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSelectionTopAppBar(
    selectedCount: Int,
    operationInFlight: Boolean,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            SnapFindButton(
                text = "Download",
                onClick = onDownloadSelected,
                enabled = !operationInFlight,
                variant = SnapFindButtonVariant.Tonal,
                fillWidth = false,
            )
            IconButton(onClick = onDeleteSelected, enabled = !operationInFlight) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected jobs")
            }
        },
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
    selectedJobs: Set<Long>,
    selectionMode: Boolean,
    onOpenJob: (Long) -> Unit,
    onToggleSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
        jobs.chunked(2).forEach { rowJobs ->
            Row(horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)) {
                rowJobs.forEach { job ->
                    JobHistoryCard(
                        modifier = Modifier.weight(1f),
                        job = job,
                        selectionMode = selectionMode,
                        isSelected = job.id in selectedJobs,
                        // While selecting, a tap toggles instead of opening --
                        // the same rule as the results grid, so the gesture
                        // doesn't change meaning between the two screens.
                        onClick = { if (selectionMode) onToggleSelected(job.id) else onOpenJob(job.id) },
                        onLongClick = { onToggleSelected(job.id) },
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
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Squarer than it was: the card carried a timestamp line under the match
    // count, and dropping that line left a tall card mostly showing crop.
    // Long-press starts selecting, the same gesture the results grid uses.
    SnapFindPhotoCard(
        modifier = modifier,
        aspectRatio = 1f,
        selected = if (selectionMode) isSelected else null,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        if (job.previewPhoto != null) {
            AsyncImage(
                model = job.previewPhoto,
                contentDescription = "Job with ${job.matchCount} matched photos",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            }
        }

        if (selectionMode) {
            SnapFindSelectionIndicator(
                selected = isSelected,
                modifier = Modifier.align(Alignment.TopStart).padding(SnapFindSpacing.xs),
            )
        }

        // The match count alone. The time a job ran told the user nothing
        // they could act on -- two jobs from the same afternoon are told
        // apart by their photos, not their clocks -- and it cost a second
        // line of chrome over the preview. The timestamp is still stored,
        // and still orders this grid.
        Box(
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
        }
    }
}

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
            // Matches what the ViewModel actually emits. It used to offer "or
            // a higher threshold", which named a control the app has never
            // had -- a preview is where that kind of copy survives longest,
            // because nothing fails when it drifts from the real string.
            uiState = UploadUiState.Error("No matches found. Try a clearer selfie."),
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
