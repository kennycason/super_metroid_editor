package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeLiquidOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeLiquidBg2ShimmerTest {
    @Test
    fun `lava and acid select native shimmer modes with BG Animate precedence`() {
        assertEquals(
            NativeLiquidBg2ShimmerMode.VERTICAL,
            nativeLiquidBg2ShimmerMode(0x02, NativeLiquidOptions.BG_ANIMATE),
        )
        assertEquals(
            NativeLiquidBg2ShimmerMode.HORIZONTAL,
            nativeLiquidBg2ShimmerMode(0x04, NativeLiquidOptions.BG_LIQUID),
        )
        assertEquals(
            NativeLiquidBg2ShimmerMode.VERTICAL,
            nativeLiquidBg2ShimmerMode(
                0x02,
                NativeLiquidOptions.BG_ANIMATE or NativeLiquidOptions.BG_LIQUID,
            ),
        )
        assertNull(nativeLiquidBg2ShimmerMode(0x06, NativeLiquidOptions.BG_ANIMATE))
        assertNull(nativeLiquidBg2ShimmerMode(0x02, NativeLiquidOptions.SMALL_TIDE))
    }

    @Test
    fun `vertical shimmer follows the native displacement table`() {
        val width = 3
        val height = 8
        val source = IntArray(width * height) { it / width }

        val warped = warpNativeLiquidBg2(
            source,
            width,
            height,
            NativeLiquidBg2ShimmerMode.VERTICAL,
            phase = 0,
        )

        assertContentEquals(
            intArrayOf(0, 2, 3, 3, 4, 4, 5, 7),
            IntArray(height) { y -> warped[y * width] },
        )
    }

    @Test
    fun `horizontal shimmer shifts and wraps each scanline`() {
        val width = 4
        val height = 9
        val source = IntArray(width * height) { it % width }

        val warped = warpNativeLiquidBg2(
            source,
            width,
            height,
            NativeLiquidBg2ShimmerMode.HORIZONTAL,
            phase = 0,
        )

        assertContentEquals(intArrayOf(0, 1, 2, 3), warped.sliceArray(0 until 4))
        assertContentEquals(intArrayOf(1, 2, 3, 0), warped.sliceArray(2 * width until 3 * width))
        assertContentEquals(intArrayOf(3, 0, 1, 2), warped.sliceArray(8 * width until 9 * width))
    }

    @Test
    fun `native phases hold for four and six frames`() {
        assertEquals(4, NativeLiquidBg2ShimmerMode.VERTICAL.framesPerPhase)
        assertEquals(6, NativeLiquidBg2ShimmerMode.HORIZONTAL.framesPerPhase)
    }
}
