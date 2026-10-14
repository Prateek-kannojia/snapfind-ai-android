package com.quantumweavers.snapfindai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.quantumweavers.snapfindai.ui.theme.SnapFindAITheme
import com.quantumweavers.snapfindai.ui.theme.SnapFindSpacing

/**
 * The single place that decides what "selected" looks like anywhere in the
 * app. Built on [SnapFindStatusBadge], so a selection tick and a saved tick
 * are the same shape and the same size -- they differ only in colour, which
 * is the one thing that should distinguish them.
 *
 * **Why the two states differ in more than fill.** They used to be a coral
 * filled circle and a coral *ring* -- the same hue, the same position, the
 * same size, differing only in whether the middle was solid. On a photograph
 * an unselected coral ring reads as a deliberate marker rather than an empty
 * checkbox, so the grid was ambiguous at exactly the moment selection matters.
 * Now selected is a coral disc with a dark tick and unselected is a plain
 * white disc: different colour, different presence of a glyph, both always
 * legible because the disc supplies its own background.
 *
 * **Why white, specifically, for unselected.** It has to say "nothing yet"
 * without belonging to the palette's vocabulary of meaningful colours --
 * coral means act, sage means done. A neutral disc is the only thing left
 * that means neither, and white is the one neutral that holds on both a dark
 * and a pale photo.
 *
 * Purely visual: the selection state a screen reader announces comes from the
 * tile's own semantics (see [SnapFindPhotoCard]), not from this icon.
 */
@Composable
fun SnapFindSelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    SnapFindStatusBadge(
        icon = if (selected) Icons.Default.Check else null,
        contentDescription = null,
        modifier = modifier,
        container = if (selected) MaterialTheme.colorScheme.primary else SnapFindUnselectedDisc,
        content = MaterialTheme.colorScheme.onPrimary,
    )
}

/**
 * Not a theme role, on purpose. This is the absence of a state, and the
 * palette's roles all carry meaning -- borrowing one of them here would say
 * something the badge doesn't mean. It is also the same value in light and
 * dark, because what it sits on is a photograph, which has no theme.
 */
private val SnapFindUnselectedDisc = Color(0xFFFFFFFF)

// On the grounds these actually land on. A pale photo is the hard case for
// the unselected disc and a dark one for the tick, so both are here.
@Composable
private fun SelectionIndicatorPreviewContent() {
    Row(
        modifier = Modifier.padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        listOf(Color(0xFF2E3A46), Color(0xFFF2EFE9)).forEach { photo ->
            Row(
                modifier = Modifier
                    .background(photo)
                    .padding(SnapFindSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.sm),
            ) {
                SnapFindSelectionIndicator(selected = true)
                SnapFindSelectionIndicator(selected = false)
            }
        }
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