package com.supermetroid.editor.ui

internal fun liquidPhysicsIndex(fxType: Int): Int = (fxType and 0xF) shr 1

/** Per-frame scroll speed (dx, dy) in pixels for L3 overlay animation by fxType. */
internal fun layer3ScrollSpeed(fxType: Int): Pair<Float, Float> = when (fxType) {
    0x02 -> Pair(0.0f, 0.0f)      // Lava — static (surface at liquid level)
    0x04 -> Pair(0.0f, 0.0f)      // Acid — static
    0x06 -> Pair(-0.4f, 0.0f)     // Water — slow leftward drift (Y oscillation added in loop)
    0x08 -> Pair(0.1f, 0.5f)      // Spores — slow downward drift
    0x0A -> Pair(1.0f, 4.0f)      // Rain — diagonal downward-right
    0x0C -> Pair(0.5f, 0.1f)      // Fog — rightward drift
    0x0E -> Pair(0.3f, 0.15f)     // Haze — slow rightward + down
    0x10 -> Pair(0.4f, 0.1f)      // Dense Fog — rightward drift
    0x20 -> Pair(1.0f, 0.0f)      // Sky Scrolling — rightward
    0x2C -> Pair(0.2f, 0.1f)      // Haze (dark) — slow drift
    else -> Pair(0f, 0f)           // No scroll animation
}

/**
 * Layer 3 ARGB palette for static preview by fxType.
 * Color 0 = transparent, colors 1-3 = increasing intensity.
 * L3 tiles use pixel value 3 (background fill) with scattered 1s/2s (features like rain drops, fog wisps).
 */
internal fun layer3Palette(fxType: Int): IntArray = when (fxType) {
    0x02 -> // Lava — fiery orange surface (subtractive in-game, use moderate alpha)
        intArrayOf(0x00000000, 0x60FF8020, 0x40FF6010, 0x20FF4000)
    0x04 -> // Acid — toxic yellow-green surface (subtractive in-game)
        intArrayOf(0x00000000, 0x60B0FF00, 0x4070B000, 0x20406000)
    0x06 -> // Water — extremely subtle, only faint bubble outlines (subtractive in-game)
        intArrayOf(0x00000000, 0x206888B0, 0x14405870, 0x08182838)
    0x08 -> // Spores — green particles
        intArrayOf(0x00000000, 0xD0A0FFA0.toInt(), 0xA060C060.toInt(), 0x30103010)
    0x0A -> // Rain — subtle translucent drops (reduced alpha to avoid dense grid look)
        intArrayOf(0x00000000, 0x60C0D8FF, 0x408090C0, 0x18102030)
    0x0C, 0x0E, 0x10 -> // Fog / Haze / Dense Fog — warm haze tint
        intArrayOf(0x00000000, 0xA0FF8040.toInt(), 0x80C06030.toInt(), 0x40804020.toInt())
    0x16 -> // Firefleas — yellow glows
        intArrayOf(0x00000000, 0xD0FFFF60.toInt(), 0xA0C0C040.toInt(), 0x30101000)
    0x1C -> // Heat Shimmer — orange shift
        intArrayOf(0x00000000, 0xA0FF8040.toInt(), 0x80C06030.toInt(), 0x40804020.toInt())
    0x24 -> // Fireflea FX (darken)
        intArrayOf(0x00000000, 0x50000000, 0x70000000, 0x90000000.toInt())
    0x26 -> // 4 Statues
        intArrayOf(0x00000000, 0xB0E0C080.toInt(), 0x90B09060.toInt(), 0x50604020)
    0x28, 0x2A -> // Ceres Elevator / Ceres Ridley
        intArrayOf(0x00000000, 0x50000000, 0x70000000, 0xA0000000.toInt())
    0x2C -> // Haze (dark)
        intArrayOf(0x00000000, 0x80000000.toInt(), 0xC0000000.toInt(), 0xF0000000.toInt())
    else -> // Default: subtle white tint
        intArrayOf(0x00000000, 0x80FFFFFF.toInt(), 0x60FFFFFF, 0x30FFFFFF)
}

internal fun richOverlayLabel(overlay: TileOverlay, bts: Int): String = when (overlay) {
    TileOverlay.DOOR -> "D$bts"
    TileOverlay.SHOT_BEAM -> when {
        bts in 0x04..0x07 -> "X?"
        else -> "Xb"
    }
    TileOverlay.SHOT_SUPER -> if (bts == 0x0B) "Xs!" else "Xs"
    TileOverlay.SHOT_PB -> if (bts == 0x09) "Xp!" else "Xp"
    TileOverlay.CRUMBLE -> when {
        bts in 0x04..0x07 -> "C!"
        bts == 0x0B -> "CE"
        else -> "C"
    }
    TileOverlay.BOMB -> if (bts in 0x04..0x07) "B!" else "B"
    else -> overlay.shortLabel
}
