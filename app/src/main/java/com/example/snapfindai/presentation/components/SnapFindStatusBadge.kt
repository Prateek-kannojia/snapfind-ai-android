package com.example.snapfindai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

/**
 * The one icon-overlay look used on top of a photo tile: a filled disc with a
 * glyph centred in it.
 *
 * **Why a disc and not a bare tinted icon.** This badge lands on an arbitrary
 * photograph -- a bright sky, a pale beach, a dark waterfall -- and a bare
 * glyph has to win against whatever happens to be behind it. It can't: there
 * is no single tint that reads on every photo. The disc gives the glyph a
 * known background, so the only contrast that has to hold is [content]
 * against [container], which is a pairing the palette controls and has
 * measured. The disc's own edge is carried by a shadow rather than a stroke,
 * so nothing here has a hard outline.
 *
 * **Why the colours are a pair rather than a tint.** The previous version
 * took a single `tint` defaulting to `primary`, which meant the saved badge
 * and the selection badge were the *same coral mark* -- the exact confusion
 * the palette's sage tertiary was introduced to prevent, and which Color.kt's
 * own comment claimed was already solved. Saved is sage, selected is coral,
 * and they can no longer drift into each other because each state names both
 * of its colours here.
 */
@Composable
fun SnapFindStatusBadge(
    icon: ImageVector?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.tertiary,
    content: Color = MaterialTheme.colorScheme.onTertiary,
) {
    Surface(
        modifier = modifier.size(SnapFindDimens.selectionIndicatorSize),
        shape = CircleShape,
        color = container,
        // The same lift every other raised thing in the app uses, which is
        // what keeps a pale disc legible on a pale photo without a border.
        shadowElevation = SnapFindDimens.elevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // No glyph is a meaningful state, not a missing one: an empty
            // disc is "selectable, not selected".
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = content,
                    modifier = Modifier.size(SnapFindDimens.badgeGlyph),
                )
            }
        }
    }
}

/**
 * A photo that is now in the user's gallery. Sage: finished, not actionable.
 *
 * **A different glyph from the selection tick, not just a different colour.**
 * Sage and coral are far apart in hue but close in luminance -- in dark mode
 * they measure 1.03:1 against each other -- which is precisely the pairing
 * red-green colour blindness collapses. The two badges never appear together
 * (selection replaces the status badges entirely), so this was survivable on
 * hue alone, but a mark that only works for some readers and only because of
 * where it sits is not worth defending when a better glyph exists.
 * `DownloadDone` also says the actual thing: not "correct", but "this came
 * down into your gallery".
 */
@Composable
fun SnapFindSavedBadge(modifier: Modifier = Modifier) {
    SnapFindStatusBadge(
        icon = Icons.Default.DownloadDone,
        contentDescription = "Saved to gallery",
        modifier = modifier,
        container = MaterialTheme.colorScheme.tertiary,
        content = MaterialTheme.colorScheme.onTertiary,
    )
}

/**
 * A photo whose save failed, and the one badge that is tappable. Uses the
 * exclamation rather than Material's `Error` glyph, which is itself a filled
 * disc and would have drawn a disc inside a disc.
 */
@Composable
fun SnapFindFailedBadge(modifier: Modifier = Modifier) {
    SnapFindStatusBadge(
        icon = Icons.Default.PriorityHigh,
        contentDescription = "Save failed, tap to retry",
        modifier = modifier,
        container = MaterialTheme.colorScheme.error,
        content = MaterialTheme.colorScheme.onError,
    )
}

// Every badge the app can draw, over the kind of ground they actually land on
// -- photos, not a white page. The two pale swatches are the point: a badge
// that only reads on a dark photo is the bug this component exists to stop.
@Composable
private fun StatusBadgePreviewContent() {
    Row(
        modifier = Modifier.padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        listOf(Color(0xFF3A4A5A), Color(0xFFE8E2D4), Color(0xFFF7F4EF)).forEach { photo ->
            Row(
                modifier = Modifier
                    .background(photo)
                    .padding(SnapFindSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.xs),
            ) {
                SnapFindSavedBadge()
                SnapFindSelectionIndicator(selected = true)
                SnapFindSelectionIndicator(selected = false)
                SnapFindFailedBadge()
            }
        }
    }
}

@Preview(name = "Badges - on dark, mid and pale photos")
@Composable
private fun SnapFindStatusBadgePreview() {
    SnapFindAITheme { StatusBadgePreviewContent() }
}

@Preview(name = "Badges - dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SnapFindStatusBadgeDarkPreview() {
    SnapFindAITheme(darkTheme = true) { StatusBadgePreviewContent() }
}