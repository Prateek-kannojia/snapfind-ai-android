package com.example.snapfindai.presentation.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.presentation.components.SnapFindSnackbarHost
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing
import java.io.File

/**
 * Full-screen, swipeable photo view -- tap a match in Results to get here,
 * swipe left/right to browse the rest of the job's matches.
 *
 * Every action that applies to a single photo lives here rather than on the
 * grid tile: while you're looking at one photo, "download" and "remove"
 * unambiguously mean this one. That's what lets the grid carry status only,
 * with the bulk actions in its app bar.
 */
@Composable
fun PhotoViewerScreen(
    startPhotoPath: String?,
    onNavigateBack: () -> Unit,
    viewModel: PhotoViewerViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsState()
    val savingPhoto by viewModel.savingPhoto.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveToGallery = rememberGallerySaveGate(snackbarHostState)

    // Collected out here, not inside the loaded branch below: removing the
    // last photo empties the list, and the event saying so still has to be
    // heard after the content it came from has gone.
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PhotoViewerEvent.Message -> snackbarHostState.showSnackbar(event.text)
                PhotoViewerEvent.Closed -> onNavigateBack()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSavedState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (photos.isEmpty()) {
        // Black rather than the default surface, so arriving here doesn't
        // flash a white screen before the first photo resolves.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    LoadedPhotoViewer(
        photos = photos,
        // Resolved against the list this screen actually loaded, so it lands
        // on the photo the user tapped however that list came out. A photo
        // that's since been removed resolves to -1 and opens the first one
        // rather than failing.
        startIndex = photos.indexOfFirst { it.photo.absolutePath == startPhotoPath }.coerceAtLeast(0),
        savingPhoto = savingPhoto,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onSave = { match -> saveToGallery { viewModel.save(match) } },
        onRemove = { match -> viewModel.remove(match) },
    )
}

/**
 * Split out so the pager is first composed once [photos] is actually loaded
 * -- `rememberPagerState` captures its initial page on first composition, and
 * from an empty list that would always be 0, dropping the user on the first
 * photo instead of the one they tapped.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadedPhotoViewer(
    photos: List<FaceMatchResult>,
    startIndex: Int,
    savingPhoto: File?,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSave: (FaceMatchResult) -> Unit,
    onRemove: (FaceMatchResult) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.size - 1)) { photos.size }
    val current = photos.getOrNull(pagerState.currentPage)
    var confirmingRemove by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnapFindSnackbarHost(snackbarHostState) },
        topBar = {
            // Title and back only. The actions moved to the toolbar below,
            // where a thumb reaches them -- on a full-screen photo the far
            // top corner is the worst place to put the two things you do
            // most.
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${photos.size}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        // Zoom lives here rather than inside each page so the pager can see
        // it: while a photo is magnified a horizontal drag has to pan that
        // photo, not turn the page, and the only reliable way to settle that
        // argument is to stop the pager accepting drags at all.
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        // Swiping to another photo starts it unzoomed. Carrying one photo's
        // magnification onto the next lands you somewhere arbitrary in an
        // image you have not looked at yet.
        LaunchedEffect(pagerState.currentPage) {
            scale = 1f
            offset = Offset.Zero
        }

        Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            userScrollEnabled = scale <= 1f,
        ) { page ->
            // Measured here so panning can be bounded by the real viewport:
            // without it a drag can push the photo entirely off screen and
            // leave the user looking at black with no way back but a guess.
            var viewport by remember { mutableStateOf(IntSize.Zero) }

            AsyncImage(
                model = photos[page].photo,
                contentDescription = "Photo ${page + 1} of ${photos.size}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .pointerInput(page) {
                        detectTapGestures(
                            // Double-tap is the gesture people actually use;
                            // pinching to get a closer look at one face is
                            // two hands' work on a phone.
                            onDoubleTap = { tap ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    // Zoom toward the tap, so the thing you
                                    // pointed at is the thing you get.
                                    val centre = Offset(viewport.width / 2f, viewport.height / 2f)
                                    offset = ((centre - tap) * (DOUBLE_TAP_SCALE - 1f))
                                        .coerceInside(viewport, DOUBLE_TAP_SCALE)
                                }
                            },
                        )
                    }
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            offset = if (scale > 1f) {
                                (offset + pan).coerceInside(viewport, scale)
                            } else {
                                // Pinching back to fit re-centres rather than
                                // leaving the photo parked off to one side.
                                Offset.Zero
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

            if (current != null) {
                ViewerActions(
                    current = current,
                    savingPhoto = savingPhoto,
                    onSave = onSave,
                    onRemove = { confirmingRemove = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = SnapFindSpacing.xl),
                )
            }
        }
    }

    if (confirmingRemove && current != null) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove from matches?") },
            // Spelled out because the action is easy to read as "delete this
            // photo", which is the one thing it doesn't do.
            text = { Text("This takes the photo out of these results. Your own copy stays where it is, including anything already saved to your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    onRemove(current)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Keeps a pan inside the magnified photo's own edges. At scale *s* the image
 * overhangs the viewport by half the extra size in each direction, and
 * travelling further than that would drag empty space into view.
 */
private fun Offset.coerceInside(viewport: IntSize, scale: Float): Offset {
    val maxX = (viewport.width * (scale - 1f)) / 2f
    val maxY = (viewport.height * (scale - 1f)) / 2f
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}

/** Far enough in to read a face, short of the point where a phone photo turns to mush. */
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * The viewer's two actions, laid over the photo rather than handed to
 * Scaffold's floating-action-button slot.
 *
 * That slot is for a FAB, and Scaffold treats whatever is in it like one:
 * it owns the placement, re-measures it against the bar and snackbar, and
 * animates it when its content changes size. A toolbar whose first action
 * swaps between a button and a progress ring changes size on every tap,
 * which is what made it appear to shift shape when download or delete was
 * pressed. Placed in the content, its position and size are this screen's
 * and nothing re-lays them out.
 *
 * Both actions are drawn at the same fixed footprint for the same reason --
 * the ring that replaces the save button mid-save occupies exactly what the
 * button did, so the toolbar is the same width before, during and after.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewerActions(
    current: FaceMatchResult,
    savingPhoto: File?,
    onSave: (FaceMatchResult) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app's own surface rather than the viewer's black: the toolbar is a
    // piece of the app laid over a photo, not part of the photo. Everything
    // else on this screen is deliberately black to get out of the image's
    // way; this is the one thing that should not disappear into it.
    HorizontalFloatingToolbar(
        modifier = modifier,
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.background,
            toolbarContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        // One button that never swaps for something else. It used to become a
        // wavy progress ring mid-save, and a ring measures taller than an
        // icon, so the toolbar grew and shrank on every tap. Saving shows as
        // the button going inert instead; the snackbar reports the outcome.
        val saving = savingPhoto == current.photo
        IconButton(
            onClick = { onSave(current) },
            // Stays tappable once saved: the user may have deleted the
            // gallery copy since, and re-saving is a no-op when they haven't.
            enabled = !saving,
            modifier = Modifier.size(SnapFindDimens.toolbarAction),
        ) {
            Icon(
                if (current.savedAt != null) Icons.Default.CheckCircle else Icons.Default.FileDownload,
                contentDescription = when {
                    saving -> "Saving to gallery"
                    current.savedAt != null -> "Saved to gallery, tap to save again"
                    else -> "Save to gallery"
                },
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(SnapFindDimens.toolbarIcon),
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(SnapFindDimens.toolbarAction),
        ) {
            // The same bin the selection bars use. It was a remove-circle to
            // mark that this takes the photo out of the results without
            // touching the user's own copy, but a distinction drawn only in
            // an icon, on the one screen that draws it differently, teaches
            // nobody. The confirmation says it in words.
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove from matches",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(SnapFindDimens.toolbarIcon),
            )
        }
    }
}
