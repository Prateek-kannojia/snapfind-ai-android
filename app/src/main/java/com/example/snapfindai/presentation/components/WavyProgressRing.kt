package com.example.snapfindai.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

/**
 * The scalloped ring used everywhere the app is waiting on something --
 * model download, on-device face matching -- so loading reads as one
 * consistent motif rather than a different spinner per screen. A thin
 * wrapper around Material 3's real `CircularWavyProgressIndicator`, not a
 * custom-drawn effect.
 *
 * [progress] null means indeterminate (animates continuously) -- used only
 * when there's genuinely no fraction to report yet, never a fabricated one.
 *
 * [label] null draws the ring alone, for the sizes where a centred label
 * wouldn't fit anyway -- a small ring standing in for an icon in an action
 * slot, or a bare "still working" ring with its own text elsewhere.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyProgressRing(
    progress: Float?,
    label: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (progress != null) {
            CircularWavyProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(size),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            CircularWavyProgressIndicator(
                modifier = Modifier.size(size),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (label != null) {
            Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// Early, mid and indeterminate together -- a ring that looks right at 67% can
// still render wrong at a few percent, where the scallops barely start.
@Composable
private fun WavyProgressRingPreviewContent() {
    Row(
        modifier = Modifier.padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        WavyProgressRing(progress = 0.04f, label = "4%")
        WavyProgressRing(progress = 0.67f, label = "67%")
        WavyProgressRing(progress = null)
        WavyProgressRing(progress = null, size = SnapFindDimens.actionProgressSize)
    }
}

@Preview(name = "Wavy progress ring", showBackground = true, widthDp = 400)
@Composable
private fun WavyProgressRingPreview() {
    SnapFindAITheme { WavyProgressRingPreviewContent() }
}

@Preview(
    name = "Wavy progress ring - dark",
    showBackground = true,
    widthDp = 400,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WavyProgressRingDarkPreview() {
    SnapFindAITheme(darkTheme = true) { WavyProgressRingPreviewContent() }
}
