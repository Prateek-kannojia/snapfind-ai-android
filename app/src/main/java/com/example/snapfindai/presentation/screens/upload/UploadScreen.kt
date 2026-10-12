package com.example.snapfindai.presentation.screens.upload

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonSlot
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.SnapFindMark
import com.example.snapfindai.presentation.components.SnapFindMarkState
import com.example.snapfindai.presentation.components.SnapFindPhotoCard
import com.example.snapfindai.presentation.components.SnapFindSelectionIndicator
import com.example.snapfindai.presentation.components.SnapFindSnackbarHost
import com.example.snapfindai.presentation.components.SnapFindTopAppBar
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.presentation.util.Thumbnail
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.utils.DownloadNotificationHelper
import com.example.snapfindai.ui.theme.ClayPillShape
import com.example.snapfindai.ui.theme.ClayShapes
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
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

    val selfieUri by viewModel.selfieUri.collectAsState()
    val zipUri by viewModel.zipUri.collectAsState()
    val zipFileName by viewModel.zipFileName.collectAsState()
    val zipFileSizeBytes by viewModel.zipFileSizeBytes.collectAsState()

    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onSelfiePicked(uri) }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onZipPicked(context, uri) }

    LaunchedEffect(Unit) {
        viewModel.navigateToResults.collect { onNavigateToResults() }
    }

    val selectedJobs by viewModel.selectedJobs.collectAsState()
    val operationInFlight by viewModel.operationInFlight.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveToGallery = rememberGallerySaveGate(snackbarHostState)

    // Picks up whatever changed while this screen was away -- photos saved or
    // removed inside a job, or a job deleted entirely. Driven from here, on
    // resume, rather than from the Results destination's disposal, which also
    // fired on rotation and on the way *into* the photo viewer. Same pattern
    // ResultsScreen uses to re-check its own job.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UploadEvent.BatchSaveCompleted -> {
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

                is UploadEvent.JobsDeleteFailed -> snackbarHostState.showSnackbar(
                    if (event.count == 1) {
                        "Couldn't delete that job"
                    } else {
                        "Couldn't delete ${event.count} jobs"
                    }
                )
            }
        }
    }

    UploadContent(
        uiState = uiState,
        jobHistory = jobHistory,
        selfieUri = selfieUri,
        hasZip = zipUri != null,
        zipFileName = zipFileName,
        zipFileSizeBytes = zipFileSizeBytes,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UploadContent(
    uiState: UploadUiState,
    jobHistory: List<JobSummary>,
    selfieUri: Uri?,
    hasZip: Boolean,
    zipFileName: String?,
    zipFileSizeBytes: Long?,
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
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val selectionMode = selectedJobs.isNotEmpty()
    val hasSelfie = selfieUri != null
    val armed = hasSelfie && hasZip

    BackHandler(enabled = selectionMode) { onClearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnapFindSnackbarHost(snackbarHostState) },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = SnapFindSpacing.screenHorizontal),
        ) {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
                    UploadForm(
                        hasSelfie = hasSelfie,
                        selfieUri = selfieUri,
                        hasZip = hasZip,
                        zipFileName = zipFileName,
                        zipFileSizeBytes = zipFileSizeBytes,
                        onChooseSelfie = onChooseSelfie,
                        onChooseZip = onChooseZip,
                    )

                    if (uiState is UploadUiState.Error) {
                        Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
                        Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    }

                    AnimatedVisibility(
                        visible = armed,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
                            SnapFindButton(text = "Find My Photos", onClick = onSubmit)
                            Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
                            SnapFindButtonSlot { PrivacyChip() }
                        }
                    }
                }
            }

            if (jobHistory.isNotEmpty()) {
                RecentJobsHeader(totalBytes = jobHistory.sumOf { it.sizeBytes })
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (jobHistory.isEmpty()) {
                    EmptyUploadState(modifier = Modifier.fillMaxSize(), showChip = !armed)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = SnapFindSpacing.sm, bottom = SnapFindSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                        verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                    ) {
                        items(jobHistory, key = { it.id }) { job ->
                            JobHistoryCard(
                                job = job,
                                selectionMode = selectionMode,
                                isSelected = job.id in selectedJobs,
                                // The same reason the results grid does this:
                                // the app bar's actions are already disabled
                                // while a batch runs, and leaving the tiles
                                // live let a tap open a job that was being
                                // deleted, or change a selection that the
                                // batch's own cleanup then discarded.
                                enabled = !operationInFlight,
                                onClick = {
                                    if (selectionMode) onToggleJobSelected(job.id) else onOpenJob(job.id)
                                },
                                onLongClick = { onToggleJobSelected(job.id) },
                            )
                        }
                    }
                }
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
    SnapFindTopAppBar(title = "SnapFind AI")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadSelectionTopAppBar(
    selectedCount: Int,
    operationInFlight: Boolean,
    onClearSelection: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    SnapFindTopAppBar(
        title = "$selectedCount selected",
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
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            IconButton(onClick = onDeleteSelected, enabled = !operationInFlight) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete selected jobs",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 KB"
}

@Composable
private fun UploadForm(
    hasSelfie: Boolean,
    selfieUri: Uri?,
    hasZip: Boolean,
    zipFileName: String?,
    zipFileSizeBytes: Long?,
    onChooseSelfie: () -> Unit,
    onChooseZip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.sm)) {
        StepRow(
            done = hasSelfie,
            showBorder = !hasSelfie,
            title = if (hasSelfie) "Your selfie" else "Add your selfie",
            subtitle = if (hasSelfie) "Tap to change" else "One clear photo of your face",
            leading = {
                if (hasSelfie) {
                    AsyncImage(
                        model = selfieUri,
                        contentDescription = "Your selfie",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(SnapFindDimens.stepThumbnailSize).clip(CircleShape),
                    )
                } else {
                    StepBadge(number = 1)
                }
            },
            onClick = onChooseSelfie,
        )
        StepRow(
            done = hasZip,
            showBorder = !hasZip && hasSelfie,
            title = if (hasZip) (zipFileName ?: "Zip file selected") else "Add the event photos",
            subtitle = if (hasZip) {
                zipFileSizeBytes?.let(::formatBytes) ?: "Tap to change"
            } else {
                "A .zip folder from the event"
            },
            leading = {
                if (hasZip) {
                    Box(
                        modifier = Modifier
                            .size(SnapFindDimens.stepThumbnailSize)
                            .clip(ClayShapes.small)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    StepBadge(number = 2)
                }
            },
            onClick = onChooseZip,
        )
    }
}

@Composable
private fun StepRow(
    done: Boolean,
    showBorder: Boolean,
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = SnapFindDimens.minTouchTarget),
        shape = ClayShapes.medium,
        color = if (done) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (showBorder) {
            BorderStroke(SnapFindDimens.borderWidth, MaterialTheme.colorScheme.onPrimaryContainer)
        } else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SnapFindSpacing.md, vertical = SnapFindSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (done) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Change",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StepBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(SnapFindDimens.stepThumbnailSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PrivacyChip() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, ClayPillShape)
            .padding(horizontal = SnapFindSpacing.md, vertical = SnapFindSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(SnapFindDimens.chipDotSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onTertiaryContainer),
        )
        Text(
            "Runs on your phone · nothing uploaded",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun EmptyUploadState(modifier: Modifier = Modifier, showChip: Boolean) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnapFindMark(state = SnapFindMarkState.Active)
        Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
        Text(
            "Find yourself in the pile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
        Text(
            "Drop in a folder of event photos and SnapFind pulls out the ones with your face in them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = SnapFindSpacing.xl),
        )
        if (showChip) {
            Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
            PrivacyChip()
        }
    }
}

@Composable
private fun RecentJobsHeader(totalBytes: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SnapFindSpacing.md, bottom = SnapFindSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Recent Jobs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                formatBytes(totalBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(SnapFindSpacing.xs))
    }
}

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
        Spacer(modifier = Modifier.weight(1f))

        val fraction = if (state.scored != null && state.total != null && state.total > 0) {
            state.scored / state.total.toFloat()
        } else null

        WavyProgressRing(
            progress = fraction,
            label = fraction?.let { "${(it * 100).toInt()}%" },
            sizeFraction = SnapFindDimens.ringSizeFractionProminent,
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

        SnapFindButtonSlot(modifier = Modifier.padding(top = SnapFindSpacing.xl)) {
            SnapFindButton(
                text = "Cancel",
                onClick = onCancel,
                variant = SnapFindButtonVariant.Tonal,
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
    /** False while a batch download or a delete is running -- see [SnapFindPhotoCard]. */
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    SnapFindPhotoCard(
        modifier = modifier,
        aspectRatio = 1f,
        selected = if (selectionMode) isSelected else null,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
        footer = {
            Text(
                "${job.matchCount} photo${if (job.matchCount == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                formatBytes(job.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (job.previewPhoto != null) {
            AsyncImage(
                model = Thumbnail(job.previewPhoto),
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

    }
}

// ---- Previews: Design > Split view in Android Studio, no build/install needed ----

private val previewJobs = listOf(
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
            selfieUri = null,
            hasZip = false,
            zipFileName = null,
            zipFileSizeBytes = null,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - first step done", showBackground = true)
@Composable
private fun UploadIdlePartialPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = emptyList(),
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = false,
            zipFileName = null,
            zipFileSizeBytes = null,
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
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Error, with history", showBackground = true)
@Composable
private fun UploadErrorPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Error("No matches found. Try a clearer selfie."),
            jobHistory = previewJobs,
            selfieUri = null,
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
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
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
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
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Selecting jobs", showBackground = true)
@Composable
private fun UploadSelectionPreview() {
    SnapFindAITheme {
        UploadContent(
            uiState = UploadUiState.Idle,
            jobHistory = previewJobs,
            selfieUri = null,
            hasZip = false,
            zipFileName = null,
            zipFileSizeBytes = null,
            selectedJobs = setOf(1L, 2L),
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
            selfieUri = null,
            hasZip = false,
            zipFileName = null,
            zipFileSizeBytes = null,
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
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
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
            selfieUri = Uri.parse("content://preview/selfie.jpg"),
            hasZip = true,
            zipFileName = "goa-trip-2026.zip",
            zipFileSizeBytes = 412_000_000,
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}