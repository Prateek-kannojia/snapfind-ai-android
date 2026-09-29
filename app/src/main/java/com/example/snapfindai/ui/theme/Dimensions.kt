package com.example.snapfindai.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The small set of dimensions it's legitimate to fix (visual primitives,
 * minimum touch targets, upper bounds) -- as opposed to major layout
 * sections, which should be sized from content/constraints, not a number
 * copied off a screenshot.
 */
object SnapFindDimens {
    /** Android's accessibility minimum -- every tappable control respects this as a floor, never a fixed height. */
    val minTouchTarget = 48.dp
    /** Buttons grow with their container up to this, then stop -- keeps a pill button from stretching edge-to-edge on a tablet. */
    val buttonMaxWidth = 360.dp
    /** The circular selection/save badge -- a small fixed-size primitive, not a layout dimension. */
    val selectionIndicatorSize = 28.dp
    /** A waiting ring standing in for an icon in an action slot (saving the photo you're viewing) -- small enough to leave the slot's footprint unchanged, large enough for the scallops to still read. */
    val actionProgressSize = 24.dp
    /** Shared across every raised surface (buttons, the pause FAB, photo cards) so they all read as the same amount "off the page" -- Material 3's own Level2 elevation token (3dp), not a number picked to match one screenshot. */
    val elevation = 3.dp
}
