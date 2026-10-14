package com.quantumweavers.snapfindai.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import com.quantumweavers.snapfindai.ui.theme.ClayShapes
import com.quantumweavers.snapfindai.ui.theme.SnapFindSpacing

/**
 * The one snackbar in the app, for the same reason there is one app bar:
 * three screens show one, and three copies of its styling is three chances
 * to drift.
 *
 * Material's default is an inverted surface -- near-black on a light theme --
 * which reads as a notification from somewhere else rather than a line from
 * this app. Painted `background` instead, it belongs to the screen it
 * appears over; the elevation is what separates it from that screen, rather
 * than a colour nothing else in the app uses.
 *
 * The dismiss button exists because these messages report a finished batch
 * ("17 saved, 3 already in gallery"), which is worth reading at your own
 * pace and then getting out of the way of -- waiting out a timeout while a
 * bar covers the grid you are trying to look at is the thing it avoids.
 */
@Composable
fun SnapFindSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier = modifier) { data ->
        Snackbar(
            // The inset Material's own default host applies and this one
            // lost. `SnackbarHost`'s default content is `Snackbar(data)`,
            // which pads itself; calling the slot-taking overload directly
            // skips that, so the bar ran to both screen edges and the
            // dismiss button pushed it past them.
            //
            // Padding only -- no width. The bar takes what the screen gives
            // it minus the inset, which is the same on a small phone and a
            // tablet without either being measured.
            modifier = Modifier.padding(SnapFindSpacing.md),
            shape = ClayShapes.medium,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            // The dismiss slot, not the action slot: Material lays the two
            // out differently, and an X in the action slot is treated as the
            // bar's primary verb -- which it is the opposite of.
            dismissActionContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            dismissAction = {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
        ) {
            Text(data.visuals.message)
        }
    }
}
