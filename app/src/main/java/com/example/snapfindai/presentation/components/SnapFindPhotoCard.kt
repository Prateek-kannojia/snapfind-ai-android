package com.example.snapfindai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.example.snapfindai.ui.theme.ClayShapes
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

/**
 * The one photo-tile shape used everywhere a photo sits in a grid (Recent
 * Jobs, Results) -- an [aspectRatio], never a fixed height, so the tile
 * follows the grid column's actual width on any screen size instead of a
 * number copied off one screenshot. Elevated by [SnapFindDimens.elevation],
 * the same value every other raised surface in the app uses.
 *
 * [selected] carries no visual weight of its own (the overlaid
 * [SnapFindSelectionIndicator] is the whole visual language for that) -- it exists
 * so the tile reports its selection state to TalkBack, which otherwise has no
 * way to read a purely icon-based selection cue. Null means "not in a
 * selectable context", so non-selectable tiles don't announce a state at all.
 */
@Composable
fun SnapFindPhotoCard(
    modifier: Modifier = Modifier,
    aspectRatio: Float = 1f,
    shape: Shape = ClayShapes.medium,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Both sides need disambiguating: inside the semantics lambda the bare
    // name `selected` binds to this function's parameter, so the assignment
    // target needs an explicit `this.` and the value needs a different name.
    val isSelected = selected
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (isSelected != null) it.semantics { this.selected = isSelected } else it },
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = SnapFindDimens.elevation),
    ) {
        Column(
            // The click lives *inside* the card, not on it, and that position
            // is doing two jobs at once. A card clips its content to its
            // shape, so a ripple started in here is rounded -- on the card's
            // own modifier it drew to the node's rectangle and squared off
            // the corners. And clipping the card itself to fix that would
            // take the elevation shadow with it, since a shadow is drawn
            // outside the shape it falls from.
            modifier = Modifier.let {
                if (onClick != null || onLongClick != null) {
                    it.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                } else it
            },
        ) {
            // The ratio moved off the card and onto the photo. With no footer
            // the two are the same thing, so every tile that had one is
            // untouched; with a footer, the photo keeps its proportions and
            // the card grows by exactly the strip underneath.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                content = content,
            )
            if (footer != null) {
                // Painted `background`, the same colour as the screen and the
                // app bar, so a card reads as a photo sitting on the page
                // rather than a white panel stuck to it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = SnapFindSpacing.md, vertical = SnapFindSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.xs),
                    content = footer,
                )
            }
        }
    }
}

// The two tiles side by side are the whole point of this preview: selection is
// carried entirely by the tick, so it has to be obvious at a glance which of
// these is selected without a border to fall back on. A flat fill stands in
// for the photo.
@Composable
private fun PhotoCardPreviewContent() {
    Row(
        modifier = Modifier.padding(SnapFindSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.md),
    ) {
        listOf(true, false).forEach { isSelected ->
            SnapFindPhotoCard(
                modifier = Modifier.weight(1f),
                selected = isSelected,
                onClick = {},
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                SnapFindSelectionIndicator(
                    selected = isSelected,
                    modifier = Modifier.align(Alignment.TopStart).padding(SnapFindSpacing.xs),
                )
                SnapFindStatusBadge(
                    icon = Icons.Default.CheckCircle,
                    contentDescription = "Saved",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(SnapFindSpacing.xs),
                )
            }
        }
    }
}

@Preview(name = "Photo card - selected / not selected", showBackground = true, widthDp = 360)
@Composable
private fun SnapFindPhotoCardPreview() {
    SnapFindAITheme { PhotoCardPreviewContent() }
}

@Preview(
    name = "Photo card - dark",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnapFindPhotoCardDarkPreview() {
    SnapFindAITheme(darkTheme = true) { PhotoCardPreviewContent() }
}
