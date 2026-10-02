package com.example.snapfindai.presentation.screens.results

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.SnapFindPhotoCard
import com.example.snapfindai.presentation.components.SnapFindSelectionIndicator
import com.example.snapfindai.presentation.components.SnapFindStatusBadge
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing
import com.example.snapfindai.utils.DownloadNotificationHelper
import java.io.File

@Composable
fun ResultsScreen(
    onNavigateBack: () -> Unit,
    onPhotoClick: (jobId: Long, photoPath: String) -> Unit,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val saveToGallery = rememberGallerySaveGate()

    // Coming back to this screen is the moment stale state would be visible:
    // photos deleted from the gallery (which means leaving the app), and
    // photos saved or removed in the viewer that sits on top of it.
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
                    // Saving is idempotent, so "nothing new landed in your
                    // gallery" is a normal outcome and has to read as one --
                    // "0 photo(s) saved" would look like a failure.
                    val message = when {
                        event.failedCount > 0 && event.savedCount == 0 -> "Couldn't save ${event.failedCount} photo(s)"
                        event.failedCount > 0 -> "${event.savedCount} saved, ${event.failedCount} failed"
                        event.savedCount == 0 -> "Already in your gallery"
                        event.skippedCount > 0 -> "${event.savedCount} saved, ${event.skippedCount} already in gallery"
                        else -> "${event.savedCount} photo(s) saved"
                    }
                    snackbarHostState.showSnackbar(message)
                    // Nothing changed on disk means nothing worth a notification.
                    if (event.savedCount > 0 || event.failedCount > 0) {
                        DownloadNotificationHelper.showCompleted(context, event.savedCount, event.failedCount, event.lastSavedUri)
                    }
                }
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

    // While selecting, the system back gesture/button cancels the selection
    // instead of navigating away -- same convention as Photos/Gmail/Drive.
    // Disabled (and so inert) once selectionMode is false, letting back
    // behave normally and actually leave the screen.
    BackHandler(enabled = selectionMode) { onClearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ResultsHeader(
                selectionMode = selectionMode,
                selectedCount = selectedCount,
                hasMatches = loaded != null && loaded.matches.isNotEmpty(),
                notSavedCount = loaded?.notSavedCount ?: 0,
                batchRunning = loaded?.batchProgress != null,
                onBack = onNavigateBack,
                onClearSelection = onClearSelection,
                onSaveSelected = onSaveSelected,
                onRemoveSelected = onRemoveSelected,
                onSaveAll = onSaveAll,
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            loaded?.batchProgress?.let { progress ->
                // The wavy track, to match the ring the rest of the app waits
                // with -- a bar rather than a ring only because it belongs
                // directly under the app bar, where a 104dp ring would shove
                // the grid down the screen.
                LinearWavyProgressIndicator(
                    progress = { progress.completed / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No matches to display.")
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(SnapFindSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                            verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid)
                        ) {
                            itemsIndexed(state.matches, key = { _, item -> item.match.photo.absolutePath }) { index, item ->
                                MatchCard(
                                    item = item,
                                    selectionMode = state.selectionMode,
                                    isSelected = item.match.photo in state.selectedPhotos,
                                    onSaveClick = { onSaveToGallery(item.match) },
                                    onClick = { if (state.selectionMode) onToggleSelected(item.match.photo) else onPhotoClick(state.jobId, item.match.photo.absolutePath) },
                                    onLongClick = { onToggleSelected(item.match.photo) },
                                )
                            }
                        }
                    }
                }
                ResultsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        // The same waiting motif as onboarding and matching --
                        // no fraction to report here, so no label either.
                        WavyProgressRing(progress = null)
                    }
                }
            }
        }
    }
}

private val ResultsTitleStyle: @Composable () -> androidx.compose.ui.text.TextStyle = {
    // Matches the reference exactly: bold, 15sp -- titleMedium's own
    // weight/size (Medium/16sp) were only approximately right.
    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultsHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    hasMatches: Boolean,
    notSavedCount: Int,
    batchRunning: Boolean,
    onBack: () -> Unit,
    onClearSelection: () -> Unit,
    onSaveSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
    onSaveAll: () -> Unit,
) {
    AnimatedContent(
        targetState = selectionMode,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "results-app-bar",
    ) { selecting ->
        if (selecting) {
            SelectionTopAppBar(
                selectedCount = selectedCount,
                onClearSelection = onClearSelection,
                onSaveSelected = onSaveSelected,
                onRemoveSelected = onRemoveSelected,
            )
        } else {
            DefaultTopAppBar(
                hasMatches = hasMatches,
                notSavedCount = notSavedCount,
                batchRunning = batchRunning,
                onBack = onBack,
                onSaveAll = onSaveAll,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultTopAppBar(
    hasMatches: Boolean,
    notSavedCount: Int,
    batchRunning: Boolean,
    onBack: () -> Unit,
    onSaveAll: () -> Unit,
) {
    TopAppBar(
        title = { Text("Your Matches", style = ResultsTitleStyle()) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            if (hasMatches) {
                // The count states the outcome before the tap, which is what
                // keeps "already saved photos aren't downloaded twice" from
                // being a surprise. At zero there's nothing to promise, so
                // the button says so and stops being tappable rather than
                // sitting there doing nothing when pressed. Disabled rather
                // than hidden so the bar doesn't reflow under a thumb.
                SnapFindButton(
                    text = if (notSavedCount == 0) "All saved" else "Download $notSavedCount",
                    onClick = onSaveAll,
                    enabled = notSavedCount > 0 && !batchRunning,
                    variant = SnapFindButtonVariant.Tonal,
                    fillWidth = false,
                    modifier = Modifier.padding(end = SnapFindSpacing.sm),
                )
            }
        },
    )
}

/**
 * Navigation swap: back-arrow becomes Close, which just clears the selection.
 * Action context: only batch-mutation actions here (download/remove the
 * selection) -- the bulk download belongs to the non-selecting bar, not this
 * one. The download label stays the bare verb because the title already
 * carries the count; "Download 12 selected" next to "12 selected" would say
 * it twice and crowd the title off a small screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopAppBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSaveSelected: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected", style = ResultsTitleStyle()) },
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            SnapFindButton(
                text = "Download",
                onClick = onSaveSelected,
                variant = SnapFindButtonVariant.Tonal,
                fillWidth = false,
            )
            IconButton(onClick = onRemoveSelected) {
                Icon(Icons.Default.Delete, contentDescription = "Remove selected from matches")
            }
        },
    )
}

@Composable
private fun MatchCard(
    item: MatchItemState,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSaveClick: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    SnapFindPhotoCard(
        selected = if (selectionMode) isSelected else null,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        AsyncImage(
            model = item.match.photo,
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

            // Status, not actions: downloading one photo lives in the viewer
            // (tap the tile), downloading many lives in the app bar. A tile
            // that also carried a download button would put a third control
            // for the same verb on screen alongside those two.
            when (item.saveStatus) {
                // Deliberately nothing while saving. A single photo takes a
                // few hundred milliseconds, so a per-tile spinner is a flicker
                // the user is unlikely to ever actually see -- multiplied
                // across every tile in the grid. The progress bar under the
                // app bar reports the batch, and each tick appearing is the
                // per-photo feedback.
                SaveStatus.Saving -> Unit
                SaveStatus.Saved -> {
                    // Same mark, same color as the selection tick -- a saved
                    // photo and a selected one shouldn't read as two different
                    // design languages.
                    SnapFindStatusBadge(
                        icon = Icons.Default.CheckCircle,
                        contentDescription = "Saved",
                        modifier = badgeModifier,
                    )
                }
                is SaveStatus.Failed -> {
                    // The one tappable badge: retry is the only per-photo
                    // action with no bulk equivalent to fall back on.
                    SnapFindStatusBadge(
                        icon = Icons.Default.Error,
                        contentDescription = "Save failed, tap to retry",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = badgeModifier.clickable(onClick = onSaveClick),
                    )
                }
                SaveStatus.Idle -> Unit // nothing to report yet
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

/**
 * Every badge a tile can show. Failed is the reason this exists: it needs
 * real storage trouble to trigger, so this is the only place it can actually
 * be looked at. Saving is included to show what it deliberately looks like --
 * the same as Idle, since a per-tile spinner was a flicker nobody would see.
 */
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewStatusMatches),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "All saved - bulk button disabled", showBackground = true)
@Composable
private fun ResultsAllSavedPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(
                jobId = 1,
                matches = previewMatches.map { it.copy(saveStatus = SaveStatus.Saved) },
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches),
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
                // selectionMode is derived from this being non-empty -- no separate flag to set.
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches, batchProgress = BatchProgress(2, 4)),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun ResultsEmptyPreview() {
    SnapFindAITheme {
        ResultsContent(
            uiState = ResultsUiState.Loaded(jobId = 1, matches = emptyList()),
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches),
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches),
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
            uiState = ResultsUiState.Loaded(jobId = 1, matches = previewMatches),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {}, onClearSelection = {}, onSaveSelected = {}, onRemoveSelected = {},
            onSaveAll = {}, onPhotoClick = { _, _ -> }, onToggleSelected = {}, onSaveToGallery = {},
        )
    }
}
