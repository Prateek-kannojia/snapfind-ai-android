package com.example.snapfindai.presentation.util

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Runs a save once the platform will actually allow it -- see [rememberGallerySaveGate]. */
typealias GallerySaveGate = (save: () -> Unit) -> Unit

/**
 * The one place that knows saving to the gallery needs a permission on
 * Android 9 (API 28) and below and none at all on 10+ -- every screen with a
 * download action goes through this rather than repeating the version check.
 *
 * Nothing here touches notifications: a photo saves whether or not the app
 * can post a notification about it, so the two are requested independently.
 */
@Composable
fun rememberGallerySaveGate(): GallerySaveGate {
    // Which save is waiting on the permission result, so it can run the
    // moment it's granted instead of making the user tap download again.
    var pendingSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val save = pendingSave
        pendingSave = null
        if (granted) save?.invoke()
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
