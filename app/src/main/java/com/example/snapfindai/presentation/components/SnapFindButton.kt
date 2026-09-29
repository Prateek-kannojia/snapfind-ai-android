package com.example.snapfindai.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.snapfindai.ui.theme.ClayPillShape
import com.example.snapfindai.ui.theme.SnapFindAITheme
import com.example.snapfindai.ui.theme.SnapFindDimens
import com.example.snapfindai.ui.theme.SnapFindSpacing

/** Every pill button in the app (Get Started, Retry, Cancel, Find My Photos, the step buttons) shares this shape, sizing, and elevation rule -- so no two buttons drift onto their own arbitrary dimensions. */
enum class SnapFindButtonVariant { Primary, Tonal, Outlined }

private val ButtonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
private val ButtonBorderWidth = 1.5.dp

/**
 * Sizing is content-driven, not screenshot-driven: `heightIn(min = ...)`
 * respects the accessibility minimum without forcing every button to that
 * exact height, and `widthIn(max = ...)` stops a full-width button from
 * stretching edge-to-edge on a wide screen while still following its
 * container's width on a phone.
 */
@Composable
fun SnapFindButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: SnapFindButtonVariant = SnapFindButtonVariant.Primary,
    fillWidth: Boolean = true,
) {
    val sizing = modifier
        .let { if (fillWidth) it.fillMaxWidth() else it }
        .widthIn(max = SnapFindDimens.buttonMaxWidth)
        .heightIn(min = SnapFindDimens.minTouchTarget)
    // Same elevation value everywhere a button can be pressed/focused/hovered --
    // not just a resting-state elevation that flattens on interaction.
    val elevation = ButtonDefaults.buttonElevation(
        defaultElevation = SnapFindDimens.elevation,
        pressedElevation = SnapFindDimens.elevation,
        focusedElevation = SnapFindDimens.elevation,
        hoveredElevation = SnapFindDimens.elevation,
        disabledElevation = 0.dp,
    )

    when (variant) {
        SnapFindButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            elevation = elevation,
            contentPadding = ButtonContentPadding,
            modifier = sizing,
        ) { Text(text) }

        SnapFindButtonVariant.Tonal -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = elevation,
            contentPadding = ButtonContentPadding,
            modifier = sizing,
        ) { Text(text) }

        SnapFindButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            border = BorderStroke(ButtonBorderWidth, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = ButtonContentPadding,
            modifier = sizing,
        ) { Text(text, fontWeight = FontWeight.Bold) }
    }
}

/** [Box] + [Alignment.Center] wrapper for the common "button should stay centered once it hits its max width" case -- expresses the relationship structurally instead of leaving a full-width button to stretch on its own. */
@Composable
fun SnapFindButtonSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}

// Every variant in one preview, plus a disabled one -- the point is catching a
// variant that drifts off the shared shape/height/elevation, which only shows
// up when they're stacked together.
@Composable
private fun ButtonPreviewContent() {
    Column(
        modifier = Modifier.padding(SnapFindSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SnapFindSpacing.sm),
    ) {
        SnapFindButton(text = "Get Started", onClick = {})
        SnapFindButton(text = "Cancel", onClick = {}, variant = SnapFindButtonVariant.Tonal)
        SnapFindButton(text = "Find My Photos", onClick = {}, variant = SnapFindButtonVariant.Outlined)
        SnapFindButton(text = "Download", onClick = {}, variant = SnapFindButtonVariant.Tonal, fillWidth = false)
        SnapFindButton(text = "Disabled", onClick = {}, enabled = false)
    }
}

@Preview(name = "Buttons - all variants", showBackground = true, widthDp = 360)
@Composable
private fun SnapFindButtonPreview() {
    SnapFindAITheme { ButtonPreviewContent() }
}

@Preview(
    name = "Buttons - dark",
    showBackground = true,
    widthDp = 360,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SnapFindButtonDarkPreview() {
    SnapFindAITheme(darkTheme = true) { ButtonPreviewContent() }
}
