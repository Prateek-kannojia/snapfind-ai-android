package com.quantumweavers.snapfindai.presentation.screens.results

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.quantumweavers.snapfindai.domain.model.FaceMatchResult
import com.quantumweavers.snapfindai.presentation.components.SnapFindConfirmDialog
import com.quantumweavers.snapfindai.presentation.components.SnapFindFab
import com.quantumweavers.snapfindai.presentation.util.Thumbnail
import com.quantumweavers.snapfindai.presentation.components.SnapFindMark
import com.quantumweavers.snapfindai.presentation.components.SnapFindMarkState
import com.quantumweavers.snapfindai.presentation.components.SnapFindPhotoCard
import com.quantumweavers.snapfindai.presentation.components.SnapFindSelectionIndicator
import com.quantumweavers.snapfindai.presentation.components.SnapFindSnackbarHost
import com.quantumweavers.snapfindai.presentation.components.SnapFindFailedBadge
import com.quantumweavers.snapfindai.presentation.components.SnapFindSavedBadge
import com.quantumweavers.snapfindai.presentation.components.WavyProgressRing
import com.quantumweavers.snapfindai.presentation.components.SnapFindTopAppBar
import com.quantumweavers.snapfindai.presentation.util.rememberGallerySaveGate
import com.quantumweavers.snapfindai.ui.theme.ClayPillShape
import com.quantumweavers.snapfindai.ui.theme.SnapFindAITheme
import com.quantumweavers.snapfindai.ui.theme.SnapFindDimens
import com.quantumweavers.snapfindai.ui.theme.SnapFindSpacing
import com.quantumweavers.snapfindai.utils.DownloadNotificationHelper
import java.io.File

@Composable
fun ResultsScreen(
    onNavigateBack: () -> Unit,
    onPhotoClick: (jobId: Long, photoPath: String) -> Unit,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val saveToGallery = rememberGallerySaveGate(snackbarHostState)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!notificationPermissionAsked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ResultsEvent.BatchSaveCompleted -> {
                    val message = when {
                        event.failedCount > 0 && event.savedCount == 0 -> "Couldn't save ${event.failedCount} photo(s)"
                        event.failedCount > 0 -> "${event.savedCount} saved, ${event.failedCount} failed"
                        event.savedCount == 0 -> "Already in your gallery"
                        event.skippedCount > 0 -> "${event.savedCount} saved, ${event.skippedCount} already in gallery"
                        else -> "${event.savedCount} photo(s) saved"
                    }
                    snackbarHostState.showSnackbar(message)
                    if (event.savedCount > 0 || event.failedCount > 0) {
                        DownloadNotificationHelper.showCompleted(context, event.savedCount, event.failedCount, event.lastSavedUri)
                    }
                }

                is ResultsEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    ResultsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onClearSelection = { viewModel.clearSelection() },
        onSaveSelected = { saveToGallery { viewModel.saveSelected() } },
        onRemoveSelected = { viewModel.removeSelected() },
        onSaveAll = { saveToGallery { viewModel.saveAll() } },
        onPhotoClick = onPhotoClick,
        onToggleSelected = { viewModel.toggleSelected(it) },
        onSaveToGallery = { match -> saveToGallery { viewModel.saveToGallery(match) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultsContent(
    uiState: ResultsUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onClearSelection: () -> Unit,
    onSaveSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onSaveAll: () -> Unit,
    onPhotoClick: (jobId: Long, photoPath: String) -> Unit,
    onToggleSelected: (File) -> Unit,
    onSaveToGallery: (FaceMatchResult) -> Unit,
) {
    val state = uiState
    val loaded = state as? ResultsUiState.Loaded
    val selectionMode = loaded?.selectionMode == true
    val selectedCount = loaded?.selectedPhotos?.size ?: 0
    val hasMatches = loaded != null && loaded.matches.isNotEmpty()
    var confirmingRemove by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selectionMode) { onClearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnapFindSnackbarHost(snackbarHostState) },
        topBar = {
            ResultsHeader(
                selectionMode = selectionMode,
                selectedCount = selectedCount,
                operationInFlight = loaded?.operationInFlight == true,
                onBack = onNavigateBack,
                onClearSelection = onClearSelection,
                onRemoveSelected = { confirmingRemove = true },
            )
        },
        floatingActionButton = {
            ResultsFab(
                hasMatches = hasMatches,
                notSavedCount = loaded?.notSavedCount ?: 0,
                selectionMode = selectionMode,
                selectedCount = selectedCount,
                operationInFlight = loaded?.operationInFlight == true,
                onSaveAll = onSaveAll,
                onSaveSelected = onSaveSelected,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            loaded?.batchProgress?.let { progress ->
                LinearWavyProgressIndicator(
                    progress = { progress.completed / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                Text(
                    "Saving ${progress.completed} of ${progress.total}...",
                    modifier = Modifier.padding(horizontal = SnapFindSpacing.lg, vertical = SnapFindSpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (state) {
                is ResultsUiState.Loaded -> {
                    if (state.matches.isEmpty()) {
                        EmptyResultsState(modifier = Modifier.fillMaxWidth().weight(1f))
                    } else {
                        Text(
                            resultsSummary(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = SnapFindSpacing.lg, vertical = SnapFindSpacing.sm),
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(
                                start = SnapFindSpacing.sm,
                                end = SnapFindSpacing.sm,
                                top = SnapFindSpacing.sm,
                                bottom = SnapFindDimens.minTouchTarget + SnapFindSpacing.xxl,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                            verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)
                        ) {
                            itemsIndexed(state.matches, key = { _, item -> item.match.photo.absolutePath }) { index, item ->
                                MatchCard(
                                    item = item,
                                    selectionMode = state.selectionMode,
                                    isSelected = item.match.photo in state.selectedPhotos,
                                    // The same rule the app bar and the FAB
                                    // already follow, which the grid wasn't.
                                    // Left live, a tap during a removal opened
                                    // the viewer on a photo being deleted, and
                                    // a tap during a save changed a selection
                                    // that saveBatch's own cleanup then
                                    // silently discarded.
                                    enabled = !state.operationInFlight,
                                    onSaveClick = { onSaveToGallery(item.match) },
                                    onClick = {
                                        if (state.selectionMode) onToggleSelected(item.match.photo)
                                        // Only reachable with a real job, since a tile exists
                                        // only where a match does -- so the null branch is
                                        // unreachable rather than silently ignored.
                                        else state.jobId?.let { onPhotoClick(it, item.match.photo.absolutePath) }
                                    },
                                    onLongClick = { onToggleSelected(item.match.photo) },
                                )
                            }
                        }
                    }
                }
                ResultsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        WavyProgressRing(progress = null)
                    }
                }
                is ResultsUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(SnapFindSpacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (confirmingRemove) {
        SnapFindConfirmDialog(
            title = if (selectedCount == 1) {
                "Remove this photo from matches?"
            } else {
                "Remove $selectedCount photos from matches?"
            },
            body = "This takes ${if (selectedCount == 1) "it" else "them"} out of these results. " +
                "Your own copy stays where it is, including anything already saved to your gallery.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingRemove = false
                onRemoveSelected()
            },
            onDismiss = { confirmingRemove = false },
        )
    }
}

private fun resultsSummary(state: ResultsUiState.Loaded): String = buildString {
    val matchCount = state.matches.size
    append("$matchCount match${if (matchCount == 1) "" else "es"}")
    if (state.scannedCount > 0) append(" from ${state.scannedCount} photos")
    if (state.savedCount > 0) append(" · ${state.savedCount} already saved")
}

@Composable
private fun EmptyResultsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SnapFindMark(state = SnapFindMarkState.Quiet)
        Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
        Text(
            "Nothing left in this job",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
        Text(
            "Every matched photo has been removed. Photos you already saved to your gallery are still there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = SnapFindSpacing.xl),
        )
    }
}

@Composable
private fun ResultsFab(
    hasMatches: Boolean,
    notSavedCount: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    operationInFlight: Boolean,
    onSaveAll: () -> Unit,
    onSaveSelected: () -> Unit,
) {
    if (!hasMatches) return
    when {
        selectionMode -> SnapFindFab(
            label = "Save $selectedCount to gallery",
            dimmed = operationInFlight,
            onClick = onSaveSelected,
        )
        notSavedCount == 0 -> AllSavedChip()
        else -> SnapFindFab(
            label = "Save $notSavedCount to gallery",
            dimmed = operationInFlight,
            onClick = onSaveAll,
        )
    }
}

@Composable
private fun AllSavedChip() {
    Surface(
        shape = ClayPillShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = SnapFindDimens.elevation,
    ) {
        Text(
            "✓ All saved",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = SnapFindSpacing.lg, vertical = SnapFindSpacing.md),
        )
    }
}

@Composable
private fun ResultsHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    operationInFlight: Boolean,
    onBack: () -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    AnimatedContent(
        targetState = selectionMode,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "results-app-bar",
    ) { selecting ->
        if (selecting) {
            SelectionTopAppBar(
                selectedCount = selectedCount,
                operationInFlight = operationInFlight,
                onClearSelection = onClearSelection,
                onRemoveSelected = onRemoveSelected,
            )
        } else {
            DefaultTopAppBar(onBack = onBack)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultTopAppBar(onBack: () -> Unit) {
    SnapFindTopAppBar(
        title = "Your Matches",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopAppBar(
    selectedCount: Int,
    operationInFlight: Boolean,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    SnapFindTopAppBar(
        title = "$selectedCount selected",
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onRemoveSelected, enabled = !operationInFlight) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove selected from matches",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    )
}

@Composable
private fun MatchCard(
    item: MatchItemState,
    selectionMode: Boolean,
    isSelected: Boolean,
    /** False while a batch save or a removal is running -- see [SnapFindPhotoCard]. */
    enabled: Boolean,
    onSaveClick: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    SnapFindPhotoCard(
        selected = if (selectionMode) isSelected else null,
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        AsyncImage(
            model = Thumbnail(item.match.photo),
            contentDescription = "Matched Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (selectionMode) {
            SnapFindSelectionIndicator(
                selected = isSelected,
                modifier = Modifier.align(Alignment.TopStart).padding(SnapFindSpacing.xs),
            )
        }

        if (!selectionMode) {
            val badgeModifier = Modifier.align(Alignment.BottomEnd).padding(SnapFindSpacing.xs)

            when (item.saveStatus) {
                SaveStatus.Saving -> Unit
                SaveStatus.Saved -> {
                    SnapFindSavedBadge(modifier = badgeModifier)
                }
                is SaveStatus.Failed -> {
                    SnapFindFailedBadge(
                        modifier = badgeModifier.clickable(enabled = enabled, onClick = onSaveClick),
                    )
                }
                SaveStatus.Idle -> Unit
            }
        }
    }
}



private val previewMatches = listOf(
    MatchItemState(FaceMatchResult(File("m1.jpg"), 0.42f, savedAt = null), SaveStatus.Idle),
    MatchItemState(FaceMatchResult(File("m2.jpg"), 0.38f, savedAt = null), SaveStatus.Idle),
    MatchItemState(FaceMatchResult(File("m3.jpg"), 0.51f, savedAt = System.currentTimeMillis()), SaveStatus.Saved),
    MatchItemState(FaceMatchResult(File("m4.jpg"), 0.47f, savedAt = null), SaveStatus.Idle),
)

private val previewStatusMatches = listOf(
    MatchItemState(FaceMatchResult(File("s1.jpg"), 0.42f, savedAt = null), SaveStatus.Idle),
    MatchItemState(FaceMatchResult(File("s2.jpg"), 0.38f, savedAt = null), SaveStatus.Saving),
    MatchItemState(FaceMatchResult(File("s3.jpg"), 0.51f, savedAt = System.currentTimeMillis()), SaveStatus.Saved),
    MatchItemState(FaceMatchResult(File("s4.jpg"), 0.47f, savedAt = null), SaveStatus.Failed("No space left on device")),
)

@Preview(name = "Tile badges - saved and failed", showBackground = true)
@Composable
private fun ResultsTileStatusesPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewStatusMatches, scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "All saved", showBackground = true)
@Composable
private fun ResultsAllSavedPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(
                jobId = 1,
                matches = previewMatches.map { it.copy(saveStatus = SaveStatus.Saved) },
                scannedCount = 24,
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Loaded", showBackground = true)
@Composable
private fun ResultsLoadedPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Selection mode", showBackground = true)
@Composable
private fun ResultsSelectionModePreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(
                jobId = 1,
                matches = previewMatches,
                scannedCount = 24,
                selectedPhotos = setOf(previewMatches[0].match.photo),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Batch saving", showBackground = true)
@Composable
private fun ResultsBatchSavingPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, scannedCount = 24, batchProgress = BatchProgress(2, 4)),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Empty - every match removed", showBackground = true)
@Composable
private fun ResultsEmptyPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = emptyList(), scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Loading", showBackground = true)
@Composable
private fun ResultsLoadingPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loading,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Loaded - dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ResultsLoadedDarkPreview() {
    SnapFindAITheme(darkTheme = true) {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Small phone", showBackground = true, device = Devices.NEXUS_5)
@Composable
private fun ResultsSmallPhonePreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Large phone", showBackground = true, device = Devices.PIXEL_7_PRO)
@Composable
private fun ResultsLargePhonePreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, scannedCount = 24),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}
