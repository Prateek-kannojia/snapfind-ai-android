package com.example.snapfindai.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.snapfindai.ui.theme.ClayPillShape
import com.example.snapfindai.ui.theme.ClayShapes
import com.example.snapfindai.ui.theme.SnapFindAITheme

/**
 * The one confirmation dialog in the app.
 *
 * It exists because there were two, written separately, and they only agreed
 * by coincidence. A dialog is the same object whichever screen raised it --
 * the thing being confirmed changes, the way a confirmation looks and reads
 * does not -- and a second hand-built one is how the third comes out subtly
 * different.
 *
 * [destructive] picks the confirm button's whole treatment, and defaults to
 * true because every confirmation this app raises so far is asking "really
 * remove this?". A destructive confirm is filled, matching the weight of a
 * FAB rather than a bare text link -- this is the one moment the colour
 * should say "this is about to happen," and a word of text carries that less
 * than a solid red pill does. A non-destructive confirm stays a text button,
 * which is the Material default for a reason: not every confirmation needs
 * to raise its voice. The dismiss is deliberately the plainest thing in the
 * dialog either way -- the cheapest exit should not compete with the
 * decision, and that argument gets stronger, not weaker, once the confirm
 * button is filled.
 */
@Composable
fun SnapFindConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Stated rather than inherited. AlertDialog's default shape and
        // container happen to resolve to these, but a dialog is the one
        // surface that used to render in Material's lavender baseline when a
        // role went unmapped, so the two it depends on are named here where
        // the dependency is visible.
        shape = ClayShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body) },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    shape = ClayPillShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(dismissLabel)
            }
        },
    )
}

// Both real callers, so the two can be compared side by side -- which is the
// thing that was impossible while each screen built its own.
@Preview(name = "Confirm - remove a photo", showBackground = true)
@Composable
private fun SnapFindConfirmDialogPreview() {
    SnapFindAITheme {
        SnapFindConfirmDialog(
            title = "Remove from matches?",
            body = "This takes the photo out of these results. Your own copy stays " +
                "where it is, including anything already saved to your gallery.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(
    name = "Confirm - delete jobs, dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnapFindConfirmDialogDarkPreview() {
    SnapFindAITheme(darkTheme = true) {
        SnapFindConfirmDialog(
            title = "Delete 3 jobs?",
            body = "This removes 24 matched photos from the app, along with the record " +
                "of what matched, and frees 61 MB.\n\nPhotos you've already saved to " +
                "your gallery stay there.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
