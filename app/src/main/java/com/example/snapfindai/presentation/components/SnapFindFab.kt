package com.example.snapfindai.presentation.components

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

/**
 * The app's one bulk-action button, in the bottom-right corner of a grid.
 *
 * Both grids now use it, which is why it lives here rather than privately on
 * one screen. It arrived on Results first -- the bulk save moved out of the
 * app bar because a tall phone's hardest reach is its top edge and the bulk
 * action was sitting in it -- and the job grid had kept a raised pill in the
 * bar for the same job. One definition means the two can't drift into
 * different treatments of the same decision.
 *
 * [dimmed] rather than `enabled`, because `ExtendedFloatingActionButton` has
 * no `enabled` parameter: a FAB is meant to be a screen's one
 * always-available action, so Material doesn't offer one. The tap is dropped
 * and `disabled()` is set in the semantics, so a screen reader is told the
 * same thing the muted colour tells everyone else. The ripple on touch
 * survives and is a Material limitation rather than a decision -- a FAB's
 * indication is wired to its own interaction source and can't be suppressed
 * from outside.
 */
@Composable
fun SnapFindFab(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    ExtendedFloatingActionButton(
        onClick = { if (!dimmed) onClick() },
        modifier = if (dimmed) modifier.semantics { disabled() } else modifier,
        containerColor = if (dimmed) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (dimmed) {
            // Stated rather than inherited, the same rule SnapFindButton
            // follows: Material's default disabled content is onSurface at
            // 38%, which over warm clay resolves to a cold grey the palette
            // doesn't contain.
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            // The coral's dark ink, not a near-white. White on this coral
            // measures 2.71:1; the cocoa is 5.68:1 on the same unchanged fill.
            MaterialTheme.colorScheme.onPrimary
        },
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}
