package com.supermetroid.editor.libretro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibretroJoypadInputTest {
    @Test
    fun `applyButtonList clears stale buttons and applies new state`() {
        val state = intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)

        LibretroJoypadInput.applyButtonList(state, listOf(0, 0, 0, 0, 0, 0, 0, 1))

        assertEquals(0, state[0])
        assertEquals(1, state[7])
        assertEquals(0, state[12], "indices above SNES range should be cleared")
    }

    @Test
    fun `resolveState returns bitmask for joypad mask query`() {
        val state = intArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0)

        val bitmask = LibretroJoypadInput.resolveState(
            inputState = state,
            device = LibretroConstants.RETRO_DEVICE_JOYPAD,
            id = LibretroConstants.RETRO_DEVICE_ID_JOYPAD_MASK,
        )

        assertEquals((1 shl 7) or (1 shl 8), bitmask.toInt())
    }

    @Test
    fun `resolveState ignores non-joypad devices`() {
        val state = intArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0)

        val value = LibretroJoypadInput.resolveState(
            inputState = state,
            device = LibretroConstants.RETRO_DEVICE_NONE,
            id = LibretroConstants.RETRO_DEVICE_ID_JOYPAD_RIGHT,
        )

        assertEquals(0, value)
    }
}
