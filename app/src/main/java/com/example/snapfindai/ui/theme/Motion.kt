package com.example.snapfindai.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

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
    /**
     * Pressing something into clay and letting it settle. Under-damped enough
     * to read as physical, not so much that it wobbles: at
     * [Spring.DampingRatioMediumBouncy] a selected photo tile visibly rang,
     * which on a grid of twelve looked like a glitch rather than a response.
     */
    val press: SpringSpec<Float> = spring(
        dampingRatio = 0.72f,
        stiffness = Spring.StiffnessMediumLow,
    )
}