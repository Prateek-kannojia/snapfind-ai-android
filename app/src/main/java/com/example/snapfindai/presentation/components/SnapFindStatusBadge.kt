package com.example.snapfindai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * The one icon-overlay look used on top of a photo tile: a controlled-size
 * box (a small visual primitive, not a layout dimension) with a bare,
 * accent-tinted icon centered in it -- no filled circle behind it, so a
 * saved tick and a selected tick are the same mark in the same color rather
 * than two different visual languages for the same checkmark.
 *
 * [SnapFindSelectionIndicator] is this with the selection icons filled in;
 * anything that varies is only the icon and, for a genuinely different
 * meaning like a failure, the [tint].
 */
@Composable
fun SnapFindStatusBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier.size(SnapFindDimens.selectionIndicatorSize),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

// Both save states side by side, on surfaceVariant because that's the kind of
// ground they actually land on -- a photo, not a white page.
@Composable
private fun StatusBadgePreviewContent() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        SnapFindStatusBadge(icon = Icons.Default.CheckCircle, contentDescription = "Saved")
        SnapFindStatusBadge(
            icon = Icons.Default.Error,
            contentDescription = "Save failed",
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Preview(name = "Status badge - saved / failed")
@Composable
private fun SnapFindStatusBadgePreview() {
    SnapFindAITheme { StatusBadgePreviewContent() }
}

@Preview(name = "Status badge - dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SnapFindStatusBadgeDarkPreview() {
    SnapFindAITheme(darkTheme = true) { StatusBadgePreviewContent() }
}
