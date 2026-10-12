package com.example.snapfindai.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

/** A fan of photo cards with one pulled to the front: many photos, one is yours. */
enum class SnapFindMarkState { Active, Quiet, Saved }

@Composable
fun SnapFindMark(
    modifier: Modifier = Modifier,
    state: SnapFindMarkState = SnapFindMarkState.Active,
    size: Dp = SnapFindDimens.markSize,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val backFar = scheme.outlineVariant
    val backNear = scheme.secondaryContainer
    val front = when (state) {
        SnapFindMarkState.Active -> scheme.primary
        SnapFindMarkState.Quiet -> scheme.outline
        SnapFindMarkState.Saved -> scheme.tertiary
    }
    val glyph = when (state) {
        SnapFindMarkState.Active -> scheme.onPrimary
        SnapFindMarkState.Quiet -> scheme.onBackground
        SnapFindMarkState.Saved -> scheme.onTertiary
    }

    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            )
    ) {
        val u = this.size.minDimension / MarkViewport
        drawCard(u, dx = -22f, dy = -2f, degrees = -20f, color = backFar)
        drawCard(u, dx = 22f, dy = -2f, degrees = 20f, color = backNear)
        drawCard(u, dx = 0f, dy = 0f, degrees = 0f, color = front)
        if (state == SnapFindMarkState.Saved) drawTick(u, glyph) else drawSilhouette(u, glyph)
    }
}

private const val MarkViewport = 120f

private fun DrawScope.drawCard(u: Float, dx: Float, dy: Float, degrees: Float, color: Color) {
    rotate(degrees, pivot = Offset(60f * u, 60f * u)) {
        drawRoundRect(
            color = color,
            topLeft = Offset((34f + dx) * u, (28f + dy) * u),
            size = Size(52f * u, 64f * u),
            cornerRadius = CornerRadius(13f * u),
        )
    }
}

private fun DrawScope.drawSilhouette(u: Float, color: Color) {
    drawCircle(color = color, radius = 9.5f * u, center = Offset(60f * u, 51f * u))
    val shoulders = Path().apply {
        moveTo(45f * u, 81f * u)
        arcTo(
            rect = Rect(45f * u, 66f * u, 75f * u, 96f * u),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        close()
    }
    drawPath(shoulders, color)
}

private fun DrawScope.drawTick(u: Float, color: Color) {
    val tick = Path().apply {
        moveTo(47f * u, 60f * u)
        lineTo(56f * u, 70f * u)
        lineTo(74f * u, 49f * u)
    }
    drawPath(
        path = tick,
        color = color,
        style = Stroke(width = 8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

@Composable
private fun MarkPreviewContent() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(SnapFindSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(SnapFindSpacing.lg),
    ) {
        listOf(
            SnapFindMarkState.Active to "Active",
            SnapFindMarkState.Quiet to "Quiet",
            SnapFindMarkState.Saved to "Saved",
        ).forEach { (state, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SnapFindMark(state = state)
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SnapFindMark(size = SnapFindDimens.markSizeSmall)
            Text("Small", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(name = "Identity mark - states and sizes", widthDp = 420)
@Composable
private fun SnapFindMarkPreview() {
    SnapFindAITheme { MarkPreviewContent() }
}

@Preview(
    name = "Identity mark - dark",
    widthDp = 420,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnapFindMarkDarkPreview() {
    SnapFindAITheme(darkTheme = true) { MarkPreviewContent() }
}