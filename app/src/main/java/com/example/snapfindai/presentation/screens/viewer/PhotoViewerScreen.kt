package com.example.snapfindai.presentation.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.presentation.components.WavyProgressRing
import com.example.snapfindai.presentation.util.rememberGallerySaveGate
import com.example.snapfindai.ui.theme.SnapFindDimens
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
    val saveToGallery = rememberGallerySaveGate()

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
@OptIn(ExperimentalMaterial3Api::class)
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
    var confirmingRemove by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("${pagerState.currentPage + 1} / ${photos.size}") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (current != null) {
                        when {
                            // The app's one waiting motif, shrunk to an
                            // icon's size. The Box holds an IconButton's own
                            // footprint so the bar doesn't shift when the
                            // action becomes a ring mid-save.
                            savingPhoto == current.photo -> Box(
                                modifier = Modifier.size(SnapFindDimens.minTouchTarget),
                                contentAlignment = Alignment.Center,
                            ) {
                                WavyProgressRing(
                                    progress = null,
                                    size = SnapFindDimens.actionProgressSize,
                                )
                            }
                            // Stays tappable once saved: the user may have
                            // deleted the gallery copy since, and re-saving
                            // is a no-op when they haven't.
                            current.savedAt != null -> IconButton(onClick = { onSave(current) }) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Saved to gallery, tap to save again",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            else -> IconButton(onClick = { onSave(current) }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Save to gallery", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { confirmingRemove = true }) {
                            // Deliberately not a trash can: this removes the
                            // photo from these results and never touches the
                            // user's own copy of it.
                            Icon(
                                Icons.Default.RemoveCircleOutline,
                                contentDescription = "Remove from matches",
                                tint = Color.White,
                            )
                        }
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
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            AsyncImage(
                model = photos[page].photo,
                contentDescription = "Photo ${page + 1} of ${photos.size}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
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
