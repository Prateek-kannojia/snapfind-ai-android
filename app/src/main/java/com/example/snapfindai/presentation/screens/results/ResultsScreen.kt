package com.example.snapfindai.presentation.screens.results

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.snapfindai.domain.model.FaceMatchResult
import com.example.snapfindai.utils.DownloadNotificationHelper

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResultsScreen(
    onNavigateBack: () -> Unit,
    onPhotoClick: (Int) -> Unit,
    viewModel: ResultsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Only needed on Android 9 (API 28) and below -- 10+ saves via MediaStore
    // with no permission at all. Tracks which action's save is waiting on
    // the permission result, so it can be retried the moment it's granted.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingAction
        pendingAction = null
        if (granted) action?.invoke()
    }

    // Fire-and-forget: doesn't block the save itself if denied, just means
    // no system notification shows up for it -- the snackbar still does.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    fun runWithStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ResultsEvent.BatchSaveCompleted -> {
                    val message = when {
                        event.failedCount == 0 -> "${event.savedCount} photo(s) saved"
                        event.savedCount == 0 -> "Couldn't save ${event.failedCount} photo(s)"
                        else -> "${event.savedCount} saved, ${event.failedCount} failed"
                    }
                    snackbarHostState.showSnackbar(message)
                    DownloadNotificationHelper.showCompleted(context, event.savedCount, event.failedCount, event.lastSavedUri)
                }
            }
        }
    }

    val state = uiState
    val loaded = state as? ResultsUiState.Loaded
    val selectionMode = loaded?.selectionMode == true
    val selectedCount = loaded?.selectedPhotos?.size ?: 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (selectionMode) "$selectedCount selected" else "Your Matches") },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) viewModel.clearSelection() else onNavigateBack() }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = if (selectionMode) "Cancel selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { runWithStoragePermission { viewModel.saveSelected() } }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Download selected")
                        }
                        IconButton(onClick = { viewModel.removeSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove selected")
                        }
                    } else if (loaded != null && loaded.matches.isNotEmpty()) {
                        TextButton(onClick = { runWithStoragePermission { viewModel.saveAll() } }) {
                            Text("Download All")
                        }
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            loaded?.batchProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.completed / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Saving ${progress.completed} of ${progress.total}...",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (state) {
                is ResultsUiState.Loaded -> {
                    if (state.matches.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No matches to display.")
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(state.matches, key = { _, item -> item.match.photo.absolutePath }) { index, item ->
                                MatchCard(
                                    item = item,
                                    selectionMode = state.selectionMode,
                                    isSelected = item.match.photo in state.selectedPhotos,
                                    onSaveClick = { runWithStoragePermission { viewModel.saveToGallery(item.match) } },
                                    onClick = { if (state.selectionMode) viewModel.toggleSelected(item.match.photo) else onPhotoClick(index) },
                                    onLongClick = { viewModel.toggleSelected(item.match.photo) },
                                )
                            }
                        }
                    }
                }
                ResultsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MatchCard(
    item: MatchItemState,
    selectionMode: Boolean,
    isSelected: Boolean,
    onSaveClick: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.match.photo,
                contentDescription = "Matched Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                        .padding(2.dp)
                )
            }

            if (!selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    when (item.saveStatus) {
                        SaveStatus.Idle -> IconButton(onClick = onSaveClick) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Save to gallery", tint = Color.White)
                        }
                        SaveStatus.Saving -> Box(modifier = Modifier.padding(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        }
                        SaveStatus.Saved -> IconButton(onClick = {}, enabled = false) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Saved", tint = Color.White)
                        }
                        is SaveStatus.Failed -> IconButton(onClick = onSaveClick) {
                            Icon(Icons.Default.Error, contentDescription = "Save failed, tap to retry", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
