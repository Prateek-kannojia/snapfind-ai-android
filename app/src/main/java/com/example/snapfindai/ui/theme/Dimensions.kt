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
    /** The glyph inside [selectionIndicatorSize]. Material's 24dp default leaves no disc visible around it, and the disc is what makes the badge legible on an arbitrary photo. */
    val badgeGlyph = 16.dp
    /** How far a selected photo tile is pressed into the page -- a ratio, not a size, so it holds at any tile dimension. */
    const val selectedTileScale = 0.93f
    /** The identity mark at the size an empty state uses it. */
    val markSize = 96.dp
    /** The identity mark standing in for an icon beside text. */
    val markSizeSmall = 40.dp
    val stepThumbnailSize = 44.dp
    val chipDotSize = 6.dp
    const val sheetMinHeightFraction = 0.4f
    /** The one border weight in the app (outlined buttons, the onboarding sheet, an armed step row) -- shared so thickening it is a one-line change, not a three-file hunt. */
    val borderWidth = 1.5.dp
    /** A waiting ring standing in for an icon in an action slot (saving the photo you're viewing) -- small enough to leave the slot's footprint unchanged, large enough for the scallops to still read. */
    val actionProgressSize = 24.dp
    /** Where [WavyProgressRing] stops growing on a wide screen, and the floor below which the scallops and its label stop reading -- its actual size is a fraction of the space it's given, clamped between these. */
    val ringSizeMin = 80.dp
    val ringSizeMax = 160.dp
    /** [WavyProgressRing]'s size as a fraction of its available width -- the default weight for a ring that's one element among several, like Results' loading spinner. */
    const val ringSizeFraction = 0.3f
    /** The larger fraction for a ring that's the whole point of its screen, like matching-in-progress -- bigger than [ringSizeFraction] by the same ratio the old fixed 128dp/104dp pair was. */
    const val ringSizeFractionProminent = 0.36f
    /** A floating toolbar's actions sit alone over a photo with nothing around them to lend scale, so they are drawn larger than an app bar's -- at a bar's size they read as something small that drifted onto the image. */
    val toolbarAction = 56.dp
    /** The glyph inside [toolbarAction]: Material's 24dp icon looks lost in a 56dp target. */
    val toolbarIcon = 28.dp
    /** The wavy progress ring's stroke. Material's own default is thin enough that the scallops read as a hairline next to the bold figure inside the ring; this is the weight that matches it. */
    val progressStroke = 7.dp
    /** Shared across every raised surface (buttons, photo cards) so they all read as the same amount "off the page" -- Material 3's own Level2 elevation token (3dp), not a number picked to match one screenshot. */
    val elevation = 3.dp

    /**
     * The onboarding sheet's lift -- Material 3's Level4, deliberately more
     * than [elevation]'s Level2.
     *
     * Two reasons it can't share the card token. A 3dp spread reads fine
     * under something a couple of fingers wide and all but disappears along a
     * full-width edge, which is the only edge this sheet shows. And the sheet
     * is the one surface in the app that *covers* the page rather than sitting
     * on it, so it is the one thing that should read as hovering.
     *
     * The shadow is also load-bearing here rather than decorative: the sheet
     * and the page behind it are one step apart in the clay ramp (about
     * 1.1:1), so the separation is carried by lift, not by colour. It
     * replaced a hairline border that was doing that job with a hard edge, on
     * a theme whose premise is that nothing has one.
     */
    val sheetElevation = 8.dp
}
