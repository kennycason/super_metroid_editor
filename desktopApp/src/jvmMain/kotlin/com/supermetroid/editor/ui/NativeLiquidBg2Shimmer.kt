package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeLiquidOptions

/** Native bank-$88 lava/acid BG2 HDMA modes. BG Animate wins when both bits are set. */
internal enum class NativeLiquidBg2ShimmerMode(
    val framesPerPhase: Int,
    val phaseCount: Int,
) {
    VERTICAL(framesPerPhase = 4, phaseCount = 8),
    HORIZONTAL(framesPerPhase = 6, phaseCount = 16),
}

private val verticalDisplacements = intArrayOf(0, 1, 1, 0, 0, -1, -1, 0)
private val horizontalDisplacements = intArrayOf(0, 0, 1, 1, 1, 1, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0)

internal fun nativeLiquidBg2ShimmerMode(fxType: Int, fxBitC: Int): NativeLiquidBg2ShimmerMode? {
    if (fxType != 0x02 && fxType != 0x04) return null
    return when {
        fxBitC and NativeLiquidOptions.BG_ANIMATE != 0 -> NativeLiquidBg2ShimmerMode.VERTICAL
        fxBitC and NativeLiquidOptions.BG_LIQUID != 0 -> NativeLiquidBg2ShimmerMode.HORIZONTAL
        else -> null
    }
}

/**
 * Applies the native one-pixel, per-scanline BG2 displacement to an already-rendered
 * Layer 2 image. Pixel lookup wraps like the SNES BG tilemap rather than exposing an
 * empty seam along the room boundary.
 */
internal fun warpNativeLiquidBg2(
    source: IntArray,
    width: Int,
    height: Int,
    mode: NativeLiquidBg2ShimmerMode,
    phase: Int,
): IntArray {
    require(width > 0 && height > 0 && source.size == width * height) {
        "BG2 shimmer source must exactly match its positive dimensions"
    }
    val normalizedPhase = Math.floorMod(phase, mode.phaseCount)
    val result = IntArray(source.size)

    when (mode) {
        NativeLiquidBg2ShimmerMode.VERTICAL -> {
            for (y in 0 until height) {
                val displacement = verticalDisplacements[(y + normalizedPhase) % verticalDisplacements.size]
                val sourceY = Math.floorMod(y + displacement, height)
                source.copyInto(result, y * width, sourceY * width, sourceY * width + width)
            }
        }

        NativeLiquidBg2ShimmerMode.HORIZONTAL -> {
            for (y in 0 until height) {
                val displacement = horizontalDisplacements[(y + normalizedPhase) % horizontalDisplacements.size]
                val row = y * width
                for (x in 0 until width) {
                    result[row + x] = source[row + Math.floorMod(x + displacement, width)]
                }
            }
        }
    }
    return result
}
