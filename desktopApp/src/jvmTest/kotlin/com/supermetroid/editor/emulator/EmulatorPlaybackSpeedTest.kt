package com.supermetroid.editor.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmulatorPlaybackSpeedTest {

    @Test
    fun `speed button cycles 1x 2x 4x 8x 16x then back to 1x`() {
        assertEquals(2, EmulatorPlaybackSpeed.next(1))
        assertEquals(4, EmulatorPlaybackSpeed.next(2))
        assertEquals(8, EmulatorPlaybackSpeed.next(4))
        assertEquals(16, EmulatorPlaybackSpeed.next(8))
        assertEquals(1, EmulatorPlaybackSpeed.next(16))
    }

    @Test
    fun `watch speed clamps at 16x`() {
        assertEquals(16, EmulatorPlaybackSpeed.clamp(16))
        assertEquals(16, EmulatorPlaybackSpeed.clamp(99))
        assertEquals(1, EmulatorPlaybackSpeed.clamp(0))
        assertEquals("16×", EmulatorPlaybackSpeed.label(16))
    }
}
