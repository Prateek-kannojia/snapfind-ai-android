package com.example.snapfindai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindSpacing

/**
 * The single place that decides what "selected" looks like anywhere in the
 * app: which icon marks each state, and that it sits directly on the photo --
 * no filled badge behind it and no border on the tile itself. Size and tint
 * come from [SnapFindStatusBadge], the shared overlay look, so a selected tick
 * and a saved tick can't drift into different colors or shapes.
 *
 * Purely visual: the selection state a screen reader announces comes from the
 * tile's own semantics (see [SnapFindPhotoCard]), not from this icon.
 */
@Composable
fun SnapFindSelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    SnapFindStatusBadge(
        icon = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        modifier = modifier,
    )
}

// Previewed on surfaceVariant rather than the default white, since these
// always sit on top of a photo -- a tick that only reads against white would
// look fine here and disappear in the app.
@Composable
private fun SelectionIndicatorPreviewContent() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        SnapFindSelectionIndicator(selected = true)
        SnapFindSelectionIndicator(selected = false)
    }
}

@Preview(name = "Selection indicator - selected / not selected")
@Composable
private fun SnapFindSelectionIndicatorPreview() {
    SnapFindAITheme { SelectionIndicatorPreviewContent() }
}

@Preview(name = "Selection indicator - dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SnapFindSelectionIndicatorDarkPreview() {
    SnapFindAITheme(darkTheme = true) { SelectionIndicatorPreviewContent() }
}
