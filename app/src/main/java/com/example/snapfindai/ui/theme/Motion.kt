package com.example.snapfindai.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp

/**
 * The app's motion, in one place for the same reason colour and spacing are.
 *
 * Clay Wave is a material identity -- soft shapes that look extruded from a
 * slab -- so its motion should have a little give rather than easing on a
 * curve. A spring expresses that directly, and expressing it once means a
 * second animated control can't arrive with its own bounce.
 *
 * Deliberately one spec, not a set. There is exactly one kind of movement in
 * the app so far (a control responding to a touch), and a scale of specs
 * nobody has a use for yet would be tokens invented ahead of their need --
 * the thing [SnapFindDimens] exists to argue against.
 */
object SnapFindMotion {
    // The one spring's parameters, named once. Two typings of the same spring
    // follow, and stating the numbers twice is how they drift apart.
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
}