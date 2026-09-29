package com.example.snapfindai.ui.theme

import androidx.compose.ui.unit.dp

/** Semantic spacing scale -- every screen pulls gaps/padding from here instead of inventing its own dp values. */
object SnapFindSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal inset every screen uses for its outermost content padding. */
    val screenHorizontal = lg
    /** Gap between grid cells (Recent Jobs, Results). */
    val grid = sm
    /** Gap between distinct sections within a screen (e.g. form -> Recent Jobs). */
    val section = xl
}
