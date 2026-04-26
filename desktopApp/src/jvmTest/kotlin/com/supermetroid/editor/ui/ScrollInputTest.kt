package com.supermetroid.editor.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScrollInputTest {

    @Test
    fun `unmodified wheel keeps native x and y deltas`() {
        val delta = resolvePanScrollDelta(rawX = 12f, rawY = -8f, shiftPressed = false)

        assertEquals(12f, delta.x)
        assertEquals(-8f, delta.y)
    }

    @Test
    fun `shift wheel remaps linux-style vertical wheel input to horizontal pan`() {
        val delta = resolvePanScrollDelta(rawX = 0f, rawY = 3f, shiftPressed = true)

        assertEquals(3f, delta.x)
        assertEquals(0f, delta.y)
    }

    @Test
    fun `shift wheel preserves native horizontal delta when present`() {
        val delta = resolvePanScrollDelta(rawX = -5f, rawY = 2f, shiftPressed = true)

        assertEquals(-5f, delta.x)
        assertEquals(0f, delta.y)
    }
}
