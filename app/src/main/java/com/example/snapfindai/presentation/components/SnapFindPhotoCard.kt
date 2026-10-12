package com.example.snapfindai.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.snapfindai.ui.theme.ClayShapes
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindMotion
import com.example.snapfindai.ui.theme.SnapFindSpacing

/**
 * The one photo-tile shape used everywhere a photo sits in a grid (Recent
 * Jobs, Results) -- an [aspectRatio], never a fixed height, so the tile
 * follows the grid column's actual width on any screen size instead of a
 * number copied off one screenshot. Elevated by [SnapFindDimens.elevation],
 * the same value every other raised surface in the app uses.
 *
 * **[selected] now carries visual weight: the tile is pressed into the page.**
 * It used to be semantics-only, leaving the overlaid
 * [SnapFindSelectionIndicator] to say everything -- which meant judging
 * "which of these twelve are selected" from a 28dp corner mark. The obvious
 * fix, a coloured border, is the one thing this theme can't have: Clay Wave's
 * premise is that almost nothing on screen has a sharp corner, and a hard
 * rectangle drawn over a photograph reads as damage rather than as state.
 *
 * So the tile scales to [SnapFindDimens.selectedTileScale] and loses its
 * lift, which on a surface that looks extruded from clay reads as a thumb
 * pressing a shape back into the slab. It is legible across a whole grid, it
 * adds no new colour, and it has no edge. The scale is animated with
 * [SnapFindMotion.press] so a tap is a press rather than a jump; the
 * elevation animates with it, because a shape going *into* a surface casting
 * the same shadow as one standing proud of it is the tell that it's a trick.
 *
 * Null means "not in a selectable context", so non-selectable tiles neither
 * animate nor announce a state.
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
    val pressedIn = isSelected == true
    val scale by animateFloatAsState(
        targetValue = if (pressedIn) SnapFindDimens.selectedTileScale else 1f,
        animationSpec = SnapFindMotion.press,
        label = "photo-card-press",
    )
    val lift by animateDpAsState(
        targetValue = if (pressedIn) 0.dp else SnapFindDimens.elevation,
        animationSpec = tween(),
        label = "photo-card-lift",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            // graphicsLayer rather than Modifier.scale so the shadow scales
            // with the card instead of being drawn at full size behind a
            // shrunken one.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .let { if (isSelected != null) it.semantics { this.selected = isSelected } else it },
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = lift),
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
                    .aspectRatio(aspectRatio)
                    // The other half of "pressed in". Scaling alone reads as
                    // the tile moving *away*; a recess needs the near edge to
                    // fall into shadow, so a short gradient is laid inside the
                    // top edge once selected. Drawn over the content and
                    // inside the card's clip, so it follows the corner radius
                    // without needing to know it.
                    .drawWithContent {
                        drawContent()
                        if (pressedIn) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f),
                                    0.18f to androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            )
                        }
                    },
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
                SnapFindSavedBadge(
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
