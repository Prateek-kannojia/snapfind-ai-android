package com.example.snapfindai.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion, in one place for the same reason colour and spacing are.
 *
 * Clay Wave is a material identity -- soft shapes that look extruded from a
 * slab -- so its motion should have a little give rather than easing on a
 * curve. A spring expresses that directly, and expressing it once means a
 * second animated control can't arrive with its own bounce.
 *
 * One spring, in the three typings Compose needs for it, plus the two
 * transitions built from it. The typings exist because `animateFloatAsState`,
 * `animateDpAsState` and `AnimatedVisibility` each want the spec typed to what
 * they animate -- not because there are three kinds of movement.
 */
object SnapFindMotion {
    // The one spring's parameters, named once. Three typings of the same
    // spring follow, and stating the numbers each time is how they drift.
    private const val PressDampingRatio = 0.72f
    private val PressStiffness = Spring.StiffnessMediumLow

    /**
     * Pressing something into clay and letting it settle. Under-damped enough
     * to read as physical, not so much that it wobbles: at
     * [Spring.DampingRatioMediumBouncy] a selected photo tile visibly rang,
     * which on a grid of twelve looked like a glitch rather than a response.
     */
    val press: SpringSpec<Float> = spring(
        dampingRatio = PressDampingRatio,
        stiffness = PressStiffness,
    )

    /**
     * The same spring, for a `Dp`-valued property.
     *
     * It exists because a tile's scale and its elevation have to move
     * *together* -- a shape going into a surface while its shadow fades on a
     * different curve is the tell that it's a trick, which is the whole
     * argument of [SnapFindPhotoCard]'s recess effect. The elevation was
     * animating on a bare `tween()` while the scale used [press], so the two
     * halves of one gesture ran on different timings and the file's own
     * comment claiming they moved together was wrong.
     *
     * [Dp.VisibilityThreshold] rather than no threshold, so the spring is
     * considered settled at the point a dp difference stops being visible
     * instead of chasing an ever-smaller remainder.
     */
    val pressDp: SpringSpec<Dp> = spring(
        dampingRatio = PressDampingRatio,
        stiffness = PressStiffness,
        visibilityThreshold = Dp.VisibilityThreshold,
    )

    /** The same spring again, for the size change an [appear]/[disappear] pair animates. */
    private val pressIntSize: SpringSpec<IntSize> = spring(
        dampingRatio = PressDampingRatio,
        stiffness = PressStiffness,
        visibilityThreshold = IntSize.VisibilityThreshold,
    )

    /**
     * Something arriving on screen, and leaving it: a fade carried on the same
     * spring as the height change, so the two read as one movement.
     *
     * Defined here rather than at each call site, which is what they were.
     * Three `AnimatedVisibility` blocks on the upload screen each wrote
     * `fadeIn() + expandVertically()` inline with no spec at all, so all three
     * ran on Compose's default tween while this file claimed the app had one
     * motion language -- "a second animated control can't arrive with its own
     * bounce" was exactly what had happened, three times, with Compose's
     * easing instead of a bounce. Four inline spec expressions also had to be
     * kept in step by hand.
     *
     * These are the places the motion is spent: the step form leaving as
     * selection mode begins, and the primary action appearing once both steps
     * are satisfied. A control that *arrives* is a reward; the spring is what
     * makes it feel like one.
     */
    val appear: EnterTransition = fadeIn(press) + expandVertically(pressIntSize)
    val disappear: ExitTransition = fadeOut(press) + shrinkVertically(pressIntSize)

    /**
     * One thing swapping for another of the same size -- the app bar changing
     * between its title and its selection mode.
     *
     * A fade with no height change, because nothing changes height: the two
     * bars occupy the same box. That is why this isn't [appear] with its
     * `expandVertically`, and it is the distinction worth keeping -- content
     * *arriving* grows into place, content being *replaced* crossfades. Two
     * transitions for two genuinely different events, on the one spring.
     *
     * Previously `fadeIn() togetherWith fadeOut()` with no spec, so the bar
     * crossfaded on Compose's default easing while the form beside it sprang.
     */
    val swapIn: EnterTransition = fadeIn(press)
    val swapOut: ExitTransition = fadeOut(press)
}
