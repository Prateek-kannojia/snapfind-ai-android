package com.quantumweavers.snapfindai.presentation.util

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** Runs a save once the platform will actually allow it -- see [rememberGallerySaveGate]. */
typealias GallerySaveGate = (save: () -> Unit) -> Unit

/**
 * The one place that knows saving to the gallery needs a permission on
 * Android 9 (API 28) and below and none at all on 10+ -- every screen with a
 * download action goes through this rather than repeating the version check.
 *
 * Nothing here touches notifications: a photo saves whether or not the app
 * can post a notification about it, so the two are requested independently.
 *
 * [snackbarHostState] is where a refusal is reported, and it is required
 * rather than optional: a denied permission used to produce nothing at all --
 * no save, no message, no state change -- so Download became a button that
 * did visibly nothing, forever, with no way for the user to tell that the
 * app was waiting on a permission. The wording lives here rather than in each
 * screen so all three say the same thing about the same refusal.
 */
@Composable
fun rememberGallerySaveGate(snackbarHostState: SnackbarHostState): GallerySaveGate {
    // Which save is waiting on the permission result, so it can run the
    // moment it's granted instead of making the user tap download again.
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val save = pendingSave
        pendingSave = null
        if (granted) {
            save?.invoke()
        } else {
            // Names the permission in the user's terms and says what it
            // costs them, because on a second refusal Android stops showing
            // the dialog at all and this message is the only remaining clue
            // that the tap wasn't simply ignored.
            scope.launch {
                snackbarHostState.showSnackbar("Storage access is needed to save photos to your gallery")
            }
        }
    }

    return remember(permissionLauncher) {
        { save ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                pendingSave = save
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                save()
            }
        }
    }
}
