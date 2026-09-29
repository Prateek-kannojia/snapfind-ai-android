package com.example.snapfindai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Soft Clay reads as "extruded from one slab" partly because almost nothing
// on screen has a sharp corner -- radii are deliberately larger than M3's
// own defaults across the board.
val ClayShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val ClayPillShape = RoundedCornerShape(50)
