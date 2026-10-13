package com.example.snapfindai.presentation.screens.upload

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.JobSummary
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindButtonSlot
import com.example.snapfindai.presentation.components.SnapFindButtonVariant
import com.example.snapfindai.presentation.components.SnapFindConfirmDialog
import com.example.snapfindai.presentation.components.SnapFindFab
import com.example.snapfindai.presentation.components.SnapFindMark
import com.example.snapfindai.presentation.components.SnapFindMarkState
import com.example.snapfindai.presentation.components.SnapFindPhotoCard
import com.example.snapfindai.presentation.components.SnapFindSelectionIndicator
import com.example.snapfindai.presentation.components.SnapFindSnackbarHost
import com.example.snapfindai.presentation.components.SnapFindTopAppBar
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.presentation.util.Thumbnail
import com.example.snapfindai.presentation.util.rememberAppearTransition
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.presentation.util.rememberSwapTransition
import com.example.snapfindai.utils.DownloadNotificationHelper
import com.example.snapfindai.ui.theme.ClayPillShape
import com.example.snapfindai.ui.theme.ClayShapes
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun UploadScreen(
    onNavigateToResults: () -> Unit,
    onOpenJob: (Long) -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    // One state, lifecycle-aware. This was eight separate collectAsState
    // calls, which is eight recomposition triggers for one screen and -- the
    // part that actually showed -- eight independently-timed values that
    // could render a combination the ViewModel never held. See
    // UploadScreenState.
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val selfieLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onSelfiePicked(uri) }

    val zipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> viewModel.onZipPicked(context, uri) }

    LaunchedEffect(Unit) {
        viewModel.navigateToResults.collect { onNavigateToResults() }
    }

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
        state = state,
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
    state: UploadScreenState,
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
    // Read off the state rather than recomputed here: both are derived
    // properties on UploadScreenState, so the screen and the ViewModel can't
    // come to different conclusions about whether a selection is active or
    // the form is ready to submit.
    val selectionMode = state.selectionMode
    val armed = state.armed

    BackHandler(enabled = selectionMode) { onClearSelection() }

    // Checked here, above the Scaffold, rather than inside its content lambda.
    // It was an early return in that lambda, which swapped the *content* and
    // nothing else: a Scaffold's slots are separate lambdas, so the app bar,
    // the snackbar host and -- visibly -- the floating action button all went
    // on composing. A FAB offering to scroll to a form, over a screen with no
    // form and no grid, was the symptom. A matching job is a different screen,
    // so it is now a different Scaffold, and nothing can leak into it.
    val processingState = state.matching as? MatchingState.Processing
    if (processingState != null) {
        MatchingScreen(state = processingState, onCancel = onCancel)
        return
    }

    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val (appear, disappear) = rememberAppearTransition()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnapFindSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Mirrors Results: the bulk action lives in the corner, not in the
            // app bar. A tall phone's hardest reach is its top edge, which is
            // where this used to be.
            when {
                // Nothing to scroll and the form already fills the screen, so
                // a button offering to take you to it would be pointing at
                // itself.
                state.history.isEmpty() -> Unit

                selectionMode -> SnapFindFab(
                    label = "Save ${state.selectedPhotoCount} to gallery",
                    dimmed = state.operationInFlight,
                    onClick = onDownloadJobs,
                )

                // The form's permanent way back. The form is free to scroll
                // out of sight precisely because this is always here -- and
                // unlike the dead summary row this replaces, it does
                // something when tapped.
                else -> SnapFindFab(
                    label = "+ New Search",
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                )
            }
        },
        topBar = {
            UploadHeader(
                selectionMode = selectionMode,
                selectedCount = state.selectedJobs.size,
                operationInFlight = state.operationInFlight,
                onClearSelection = onClearSelection,
                onDownloadSelected = onDownloadJobs,
                onDeleteSelected = { confirmingDelete = true },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Outside the horizontal inset the rest of the screen sits in, so
            // the bar runs edge to edge directly under the app bar -- the same
            // placement Results gives its own batch. A progress bar that stops
            // short of the screen edges reads as one component's business
            // rather than as the screen's current state.
            state.batchProgress?.let { BatchProgressBar(progress = it) }

            if (state.history.isEmpty()) {
                // No grid, so nothing to scroll and no lazy container needed:
                // a plain Column with the empty state taking the slack. A lazy
                // item asked to fill the viewport is the one thing lazy
                // layouts handle badly, and there is no reason to ask.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = SnapFindSpacing.screenHorizontal),
                ) {
                    PickForm(
                        state = state,
                        armed = armed,
                        appear = appear,
                        disappear = disappear,
                        onChooseSelfie = onChooseSelfie,
                        onChooseZip = onChooseZip,
                        onSubmit = onSubmit,
                    )
                    EmptyUploadState(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        showChip = !armed,
                    )
                }
            } else {
                // One scrolling surface for the whole screen, which is the
                // point of this layout. The form used to sit *outside* the
                // grid in a fixed Column, so it never moved and the grid was
                // left scrolling inside whatever space was left over -- on a
                // short history, a small window beneath a large dead zone.
                // Now the form scrolls away, the app bar goes with it, and the
                // grid gets the screen.
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = SnapFindSpacing.screenHorizontal,
                        end = SnapFindSpacing.screenHorizontal,
                        // Room for the FAB, which floats over this grid and
                        // would otherwise sit on top of the last row of
                        // cards. The same reserve the results grid keeps for
                        // the same reason.
                        bottom = SnapFindDimens.minTouchTarget + SnapFindSpacing.xxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                    verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.grid),
                ) {
                    // Present even while hidden, rather than conditional: an
                    // item removed from a lazy list vanishes instantly, where
                    // AnimatedVisibility inside a kept item still collapses it
                    // on the spring.
                    item(span = { GridItemSpan(maxLineSpan) }, key = "pick-form") {
                        AnimatedVisibility(
                            visible = !selectionMode,
                            enter = appear,
                            exit = disappear,
                        ) {
                            PickForm(
                                state = state,
                                armed = armed,
                                appear = appear,
                                disappear = disappear,
                                onChooseSelfie = onChooseSelfie,
                                onChooseZip = onChooseZip,
                                onSubmit = onSubmit,
                            )
                        }
                    }

                    items(state.history, key = { it.id }) { job ->
                        JobHistoryCard(
                            job = job,
                            selectionMode = selectionMode,
                            isSelected = job.id in state.selectedJobs,
                            // The same reason the results grid does this:
                            // the app bar's actions are already disabled
                            // while a batch runs, and leaving the tiles
                            // live let a tap open a job that was being
                            // deleted, or change a selection that the
                            // batch's own cleanup then discarded.
                            enabled = !state.operationInFlight,
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

    if (confirmingDelete) {
        val selected = state.history.filter { it.id in state.selectedJobs }
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
    SnapFindConfirmDialog(
        title = if (jobs.size == 1) "Delete this job?" else "Delete ${jobs.size} jobs?",
        // Copy unchanged from the hand-built dialog this replaces.
        body = "This removes $photoCount matched photo${if (photoCount == 1) "" else "s"} " +
            "from the app, along with the record of what matched, and frees $freed.\n\n" +
            "Photos you've already saved to your gallery stay there.",
        confirmLabel = "Delete",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The two step rows, any error from the last run, and the primary action.
 *
 * Extracted because it is now placed twice: as a full-span item inside the
 * grid when there is a history to scroll, and in a plain Column when there
 * isn't. One definition, so the two paths cannot drift.
 */
@Composable
private fun PickForm(
    state: UploadScreenState,
    armed: Boolean,
    appear: EnterTransition,
    disappear: ExitTransition,
    onChooseSelfie: () -> Unit,
    onChooseZip: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column {
        Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
        UploadForm(
            selfieUri = state.selfieUri,
            archive = state.archive,
            onChooseSelfie = onChooseSelfie,
            onChooseZip = onChooseZip,
        )

        val error = state.matching as? MatchingState.Error
        if (error != null) {
            Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
            Text(error.message, color = MaterialTheme.colorScheme.error)
        }

        // The CTA arrives rather than sitting greyed out waiting -- a control
        // that appears is a reward, one that has been grey for a minute is
        // furniture. This is the one place the audit asked the motion to be
        // spent, and it now spends the app's spring rather than Compose's
        // default easing.
        AnimatedVisibility(visible = armed, enter = appear, exit = disappear) {
            Column {
                Spacer(modifier = Modifier.height(SnapFindSpacing.lg))
                SnapFindButton(text = "Find My Photos", onClick = onSubmit)
                Spacer(modifier = Modifier.height(SnapFindSpacing.sm))
                SnapFindButtonSlot { PrivacyChip() }
            }
        }
        Spacer(modifier = Modifier.height(SnapFindSpacing.section))
    }
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
    val (swapIn, swapOut) = rememberSwapTransition()
    AnimatedContent(
        targetState = selectionMode,
        // The one spring, like everything else that moves on this screen --
        // and nothing at all when the user has turned animation off.
        transitionSpec = { swapIn togetherWith swapOut },
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

@Composable
private fun UploadDefaultTopAppBar() {
    SnapFindTopAppBar(title = "SnapFind AI")
}

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
            // Save is not here any more -- it is the corner FAB, the same
            // place Results puts it. What stays is the destructive action,
            // also like Results: delete sits far from the thumb on purpose.
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
    selfieUri: Uri?,
    archive: PickedArchive?,
    onChooseSelfie: () -> Unit,
    onChooseZip: () -> Unit,
) {
    val hasSelfie = selfieUri != null
    // One nullable value answers all three questions the second row asks --
    // whether an archive is picked, what it's called, and how big it is --
    // instead of three fields that could describe different archives.
    val hasZip = archive != null
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
            title = if (hasZip) (archive?.name ?: "Zip file selected") else "Add the event photos",
            subtitle = if (hasZip) {
                archive?.sizeBytes?.let(::formatBytes) ?: "Tap to change"
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
            // The sequence is enforced, not merely suggested. The design
            // audit argued for "recessed but tappable -- guide the order,
            // don't enforce it", and the cost asymmetry here decides against
            // it: the archive is the expensive pick, often a long browse for
            // a file of a few hundred megabytes, and a user who does that
            // first only finds out afterwards that the cheap pick was
            // required. Blocking the expensive step costs one extra tap;
            // allowing it out of order costs a wasted browse.
            //
            // What the audit was right about is that a disabled control must
            // not be a dead end. The dimming says "not yet" to anyone who can
            // see it and [disabledReason] says it to anyone who can't.
            enabled = hasSelfie,
            disabledReason = "Unavailable until you add your selfie",
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
    /**
     * False for a step whose turn hasn't come. Reaches `Surface`'s own
     * `enabled`, so the row stops taking taps *and* reports itself as disabled
     * -- the same rule the photo tiles and the save FAB follow, for the same
     * reason: a control dimmed only in colour still tells a screen reader it
     * is actionable.
     */
    enabled: Boolean = true,
    /** Said to a screen reader in place of the dimming a sighted user sees. */
    disabledReason: String? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SnapFindDimens.minTouchTarget)
            // The visible subtitle deliberately doesn't change when the step
            // is unavailable -- it keeps describing what the step is for. That
            // leaves a sighted user reading the dimming and a screen-reader
            // user with nothing, so the reason goes in the semantics instead.
            .let { base ->
                if (!enabled && disabledReason != null) {
                    base.semantics { stateDescription = disabledReason }
                } else {
                    base
                }
            },
        shape = ClayShapes.medium,
        color = when {
            done -> MaterialTheme.colorScheme.secondaryContainer
            // The app's one disabled container, the same value
            // SnapFindButton states explicitly rather than inheriting
            // Material's cold-grey composite over warm clay.
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
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


/**
 * What a batch over the selected jobs is doing.
 *
 * Downloading or deleting several jobs used to disable the app bar's actions
 * and the grid and then say nothing for as long as the work took, which on a
 * few hundred full-resolution photos is long enough to read as the app having
 * stopped responding. The progress was already being computed for the
 * download; nothing drew it, and deleting didn't compute any.
 *
 * Indeterminate until the counts exist -- for a download that is the window
 * where every selected job's matches are being read out of the database. A
 * determinate bar pinned at 0% would claim the work had started and stalled,
 * which is the one thing it must not say.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BatchProgressBar(progress: JobBatchProgress) {
    Column {
        val fraction = progress.fraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        } else {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        }
        Text(
            progress.label(),
            modifier = Modifier.padding(
                horizontal = SnapFindSpacing.screenHorizontal,
                vertical = SnapFindSpacing.xs,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Names the unit, because the two batches don't share one: a download walks
 * the photos inside every selected job, a delete walks the jobs. "3 of 12"
 * alone would be wrong half the time.
 */
private fun JobBatchProgress.label(): String = when (action) {
    JobBatchProgress.Action.Saving ->
        if (completed != null && total != null) {
            "Saving $completed of $total photo${if (total == 1) "" else "s"}..."
        } else {
            // The counts genuinely aren't known yet rather than being zero.
            "Preparing to save..."
        }

    JobBatchProgress.Action.Deleting ->
        if (total != null && total > 1) {
            "Deleting ${completed ?: 0} of $total jobs..."
        } else {
            "Deleting..."
        }
}

/**
 * The whole screen while a job runs: an app bar and the ring, and nothing
 * else.
 *
 * Its own Scaffold on purpose. Sharing the upload screen's meant sharing every
 * slot it declares, and a slot does not know that the content lambda decided
 * to render something different -- which is how a floating action button
 * ended up over a screen it had no business on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchingScreen(
    state: MatchingState.Processing,
    onCancel: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SnapFindTopAppBar(title = "SnapFind AI") },
    ) { padding ->
        MatchingProgress(
            modifier = Modifier.padding(padding),
            state = state,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun MatchingProgress(
    modifier: Modifier = Modifier,
    state: MatchingState.Processing,
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

private val previewArchive = PickedArchive(
    uri = Uri.parse("content://preview/goa-trip-2026.zip"),
    name = "goa-trip-2026.zip",
    sizeBytes = 412_000_000,
)

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
            state = UploadScreenState(
                matching = MatchingState.Idle,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - first step done", showBackground = true)
@Composable
private fun UploadIdlePartialPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - both picked, with history", showBackground = true)
@Composable
private fun UploadIdleReadyPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
                archive = previewArchive,
                history = previewJobs,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Error, with history", showBackground = true)
@Composable
private fun UploadErrorPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Error("No matches found. Try a clearer selfie."),
                archive = previewArchive,
                history = previewJobs,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Matching - indeterminate", showBackground = true)
@Composable
private fun UploadMatchingIndeterminatePreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Processing(),
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
                archive = previewArchive,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Matching - in progress", showBackground = true)
@Composable
private fun UploadMatchingProgressPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Processing(scored = 18, total = 24),
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
                archive = previewArchive,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Selecting jobs", showBackground = true)
@Composable
private fun UploadSelectionPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                history = previewJobs,
                selectedJobs = setOf(1L, 2L),
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Batch - downloading", showBackground = true)
@Composable
private fun UploadDownloadingPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                history = previewJobs,
                selectedJobs = setOf(1L, 2L),
                operationInFlight = true,
                batchProgress = JobBatchProgress(JobBatchProgress.Action.Saving, 5, 16),
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

// The window before the photo count is known -- an indeterminate bar, not a
// determinate one sitting at zero.
@Preview(name = "Batch - preparing to download", showBackground = true)
@Composable
private fun UploadPreparingDownloadPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                history = previewJobs,
                selectedJobs = setOf(1L, 2L),
                operationInFlight = true,
                batchProgress = JobBatchProgress(JobBatchProgress.Action.Saving),
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Batch - deleting", showBackground = true)
@Composable
private fun UploadDeletingPreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                history = previewJobs,
                selectedJobs = setOf(1L, 2L, 3L),
                operationInFlight = true,
                batchProgress = JobBatchProgress(JobBatchProgress.Action.Deleting, 1, 3),
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Idle - dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UploadIdleDarkPreview() {
    SnapFindAITheme(darkTheme = true) {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                history = previewJobs,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Small phone", showBackground = true, device = Devices.NEXUS_5)
@Composable
private fun UploadSmallPhonePreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
                archive = previewArchive,
                history = previewJobs,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}

@Preview(name = "Large phone", showBackground = true, device = Devices.PIXEL_7_PRO)
@Composable
private fun UploadLargePhonePreview() {
    SnapFindAITheme {
        UploadContent(
            state = UploadScreenState(
                matching = MatchingState.Idle,
                selfieUri = Uri.parse("content://preview/selfie.jpg"),
                archive = previewArchive,
                history = previewJobs,
            ),
            onChooseSelfie = {}, onChooseZip = {}, onSubmit = {}, onCancel = {}, onOpenJob = {},
        )
    }
}