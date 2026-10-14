package com.quantumweavers.snapfindai.presentation.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.quantumweavers.snapfindai.ui.theme.ClayPillShape
import com.quantumweavers.snapfindai.ui.theme.SnapFindAITheme
import com.quantumweavers.snapfindai.ui.theme.SnapFindDimens
import com.quantumweavers.snapfindai.ui.theme.SnapFindSpacing

/**
 * Every pill button in the app (Get Started, Retry, Cancel, Find My Photos,
 * the step buttons) shares this shape and sizing -- so no two buttons drift
 * onto their own arbitrary dimensions.
 *
 * Elevation is the one thing the variants don't share: Primary and Tonal are
 * raised, Outlined deliberately sits flat, since an outline plus a shadow
 * reads as two competing edges around the same button.
 */
enum class SnapFindButtonVariant { Primary, Tonal, Outlined }

private val ButtonContentPadding = PaddingValues(horizontal = SnapFindSpacing.xl, vertical = SnapFindSpacing.md)

/**
 * Sizing is content-driven, not screenshot-driven: `heightIn(min = ...)`
 * respects the accessibility minimum without forcing every button to that
 * exact height, and `widthIn(max = ...)` stops a full-width button from
 * stretching edge-to-edge on a wide screen while still following its
 * container's width on a phone.
 *
 * [contentColor] overrides the variant's own label colour, for the actions
 * the design accents -- downloading photos, and the icons beside it. Null
 * keeps the variant's pairing, which is what nearly every button wants: an
 * accent that appears on every button is not an accent.
 */
@Composable
fun SnapFindButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: SnapFindButtonVariant = SnapFindButtonVariant.Primary,
    fillWidth: Boolean = true,
    contentColor: Color? = null,
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

    // Material's default disabled treatment is `onSurface` at 12% for the
    // container and 38% for the label. Composited over warm putty that
    // resolves to a cold grey the palette doesn't contain, with the label
    // measuring 1.56:1 against its own container on a real device -- the one
    // genuinely ugly element in the app, and unreadable besides. Stated
    // explicitly instead, in the clay family, and shared by all three
    // variants so a disabled button looks the same whatever it is.
    //
    // This lands at 4.33:1 light / 4.53:1 dark. Light is just under AA's
    // 4.5:1, and that is deliberate rather than an oversight: WCAG 1.4.3
    // exempts inactive components from contrast requirements precisely
    // because a disabled control that reads as strongly as an enabled one
    // has stopped communicating that it is disabled. 4.33:1 is legible and
    // still visibly muted, which is the whole job. The real fix for the one
    // button this was ugliest on is not a colour at all -- a primary action
    // should appear when it becomes available rather than sit greyed out
    // waiting, which is a layout change, not a palette one.
    val disabledContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val disabledContent = MaterialTheme.colorScheme.onSurfaceVariant

    when (variant) {
        SnapFindButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
            elevation = elevation,
            contentPadding = ButtonContentPadding,
            modifier = sizing,
        ) { Text(text) }

        // secondaryContainer, not surfaceVariant. surfaceVariant sits 1.07:1
        // from the page -- for practical purposes the same colour -- so a
        // tonal button was defined entirely by its 3dp drop shadow and
        // effectively invisible. secondaryContainer exists to be the "quiet
        // but still interactive" ground and is a visible shape on its own.
        SnapFindButtonVariant.Tonal -> Button(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = disabledContainer,
                disabledContentColor = disabledContent,
            ),
            elevation = elevation,
            contentPadding = ButtonContentPadding,
            modifier = sizing,
        ) { Text(text) }

        // The border here is the only thing identifying the control, which
        // WCAG 1.4.11 puts at 3:1. `outline` is deliberately softer than that
        // (it is for dividers), and the raw accent is 2.34:1 as a line on
        // clay -- so both are wrong for this. onPrimaryContainer is the
        // accent's ink, at 4.60:1, and it is what the label uses too, so the
        // edge and the word are one colour rather than two near-misses.
        SnapFindButtonVariant.Outlined -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = ClayPillShape,
            border = BorderStroke(
                SnapFindDimens.borderWidth,
                if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else disabledContainer,
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimaryContainer,
                disabledContentColor = disabledContent,
            ),
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
        SnapFindButton(text = "Cancel", onClick = {}, variant = SnapFindButtonVariant.Tonal, fillWidth = false)
        SnapFindButton(text = "Disabled", onClick = {}, enabled = false)
        SnapFindButton(text = "Choose selfie", onClick = {},variant = SnapFindButtonVariant.Tonal, enabled = true)

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
