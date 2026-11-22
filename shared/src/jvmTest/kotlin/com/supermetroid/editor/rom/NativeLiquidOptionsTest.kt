package com.supermetroid.editor.rom

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeLiquidOptionsTest {
    @Test
    fun `native liquid option masks match bank 88 behavior`() {
        assertEquals(
            mapOf(
                0x01 to "Flow Left",
                0x02 to "BG Animate",
                0x04 to "BG Liquid",
                0x40 to "Large Tide",
                0x80 to "Small Tide",
            ),
            NativeLiquidOptions.labels,
        )
        assertEquals(listOf("bg_animate", "large_tide"), NativeLiquidOptions.decodedNames(0x42))
        assertEquals(0x20, 0x22 and NativeLiquidOptions.knownMask.inv() and 0xFF)
    }
}
