package com.example.snapfindai.presentation.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.presentation.components.SnapFindButton
import com.example.snapfindai.presentation.components.SnapFindConfirmDialog
import com.example.snapfindai.presentation.components.SnapFindSnackbarHost
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.ui.theme.ClayTertiaryDark
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
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val savingPhoto by viewModel.savingPhoto.collectAsStateWithLifecycle()
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
            // Back, the position, and whether this photo is already in the
            // gallery. The two *actions* live at the bottom where a thumb
            // reaches them; what belongs up here is the one piece of state the
            // screen exists to report. "1 / 21" used to be the only thing on
            // screen -- the grid behind showed a tick for a saved photo and
            // the viewer dropped it, so the question the user opened this
            // screen to answer was the one thing it didn't answer.
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${photos.size}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (current?.savedAt != null) {
                        SavedBadge()
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
        SnapFindConfirmDialog(
            title = "Remove from matches?",
            // Spelled out because the action is easy to read as "delete this
            // photo", which is the one thing it doesn't do. Copy unchanged
            // from the hand-built dialog this replaces.
            body = "This takes the photo out of these results. Your own copy stays " +
                "where it is, including anything already saved to your gallery.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingRemove = false
                onRemove(current)
            },
            onDismiss = { confirmingRemove = false },
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
 * That slot is for a FAB, and Scaffold treats whatever is in it like one: it
 * owns the placement and re-measures it against the bar and the snackbar.
 * Placed in the content, the row's position is this screen's own and nothing
 * re-lays it out.
 *
 * Save takes the weighted share of the row and Delete stays a fixed-size
 * circle at the end, rather than two compact controls with a gap of unused
 * photo between them -- the save action is the one thing this screen is
 * mostly for, so it gets the room. They stay at opposite edges, which is what
 * keeps a destructive glyph from sitting adjacent to the one that puts a
 * photo in the gallery.
 */
@Composable
private fun ViewerActions(
    current: FaceMatchResult,
    savingPhoto: File?,
    onSave: (FaceMatchResult) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saving = savingPhoto == current.photo
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = SnapFindSpacing.screenHorizontal),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SnapFindButton rather than SnapFindFab: a FAB is sized to its own
        // label by design, which is correct for a grid's one bulk action but
        // left this single-photo save as a small pill with most of the row
        // empty beside it. SnapFindButton's own width.weight(1f) here is the
        // same "fill what you're given, capped at buttonMaxWidth" rule every
        // other primary button in the app already follows -- not a size
        // picked for this screen, the existing one.
        //
        // Saving shows as the button going inert and saying so, rather than
        // swapping for a spinner. A progress ring measures taller than a
        // label, and the toolbar used to grow and shrink on every tap because
        // of it.
        SnapFindButton(
            text = when {
                saving -> "Saving..."
                // Stays offered once saved: the user may have deleted the
                // gallery copy since, and re-saving is a no-op when they
                // haven't. The top bar already says it is saved, so the
                // button says what tapping it would do instead of repeating.
                current.savedAt != null -> "Save again"
                else -> "Save to gallery"
            },
            onClick = { onSave(current) },
            enabled = !saving,
            modifier = Modifier.weight(1f),
        )

        // errorContainer rather than error: this icon only opens the
        // confirmation below, it isn't itself the destructive act, so it
        // carries the softer of the two reds. The dialog's own confirm
        // button is full-strength error -- that's the moment the colour
        // should say "this is about to happen," not before it.
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(SnapFindDimens.toolbarAction)
                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove from matches",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(SnapFindDimens.toolbarIcon),
            )
        }
    }
}

/**
 * "Saved" in the viewer's top bar, in the palette's sage.
 *
 * Sage because it is the app's "this is done" colour, the same one the grid's
 * saved badge uses -- and the *dark*-theme sage specifically, because this bar
 * is black in both themes: `#93AD9C` measures 8.70:1 on black where the light
 * sage manages 4.76:1.
 */
@Composable
private fun SavedBadge() {
    Row(
        modifier = Modifier.padding(end = SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.DownloadDone,
            contentDescription = null,
            tint = ClayTertiaryDark,
            modifier = Modifier.size(SnapFindDimens.badgeGlyph),
        )
        Text(
            "Saved",
            style = MaterialTheme.typography.labelMedium,
            color = ClayTertiaryDark,
        )
    }
}

