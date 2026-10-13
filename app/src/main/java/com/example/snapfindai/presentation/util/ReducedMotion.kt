package com.example.snapfindai.presentation.util

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.snapfindai.ui.theme.SnapFindMotion

/**
 * True when the user has asked the system to stop animating.
 *
 * Read from `ANIMATOR_DURATION_SCALE`, which is the setting behind
 * *Settings → Accessibility → Remove animations* (and the developer-options
 * animation scales). Android zeroes it, and a platform-animated `View` honours
 * it automatically -- but Compose's own animations do not, so anything built
 * with `AnimatedVisibility` or `animate*AsState` keeps moving unless the app
 * checks. Nothing in this app was checking.
 *
 * It matters more here than in most apps: the people most likely to turn this
 * on include anyone whose vestibular condition makes movement genuinely
 * unpleasant, and this screen's form slides a couple of hundred dp.
 *
 * Read once per composition rather than observed. The setting is changed from
 * system Settings, which means leaving the app entirely, and it is not worth a
 * `ContentObserver` to catch a change made while we are in the background --
 * the next composition after returning picks it up.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * [SnapFindMotion.appear] / [SnapFindMotion.disappear], or no movement at all
 * when the user has asked for none.
 *
 * `EnterTransition.None` isn't "instant appear with a fade" -- it is the
 * absence of a transition, so the content simply is there. That is the correct
 * reading of the setting: not faster animation, no animation.
 */
@Composable
fun rememberAppearTransition(): Pair<EnterTransition, ExitTransition> =
    rememberMotion(SnapFindMotion.appear, SnapFindMotion.disappear)

/**
 * [SnapFindMotion.swapIn] / [SnapFindMotion.swapOut] for one thing replacing
 * another of the same size, or nothing when motion is off.
 */
@Composable
fun rememberSwapTransition(): Pair<EnterTransition, ExitTransition> =
    rememberMotion(SnapFindMotion.swapIn, SnapFindMotion.swapOut)

@Composable
private fun rememberMotion(
    enter: EnterTransition,
    exit: ExitTransition,
): Pair<EnterTransition, ExitTransition> {
    val reduced = rememberReducedMotion()
    return remember(reduced, enter, exit) {
        if (reduced) EnterTransition.None to ExitTransition.None else enter to exit
    }
}
