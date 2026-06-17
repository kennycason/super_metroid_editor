package com.supermetroid.editor.ui

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `zoom modifier recognizes mac command and control`() {
        assertTrue(isZoomModifierPressed(mouseEvent(InputEvent.META_DOWN_MASK)))
        assertTrue(isZoomModifierPressed(mouseEvent(InputEvent.CTRL_DOWN_MASK)))
        assertFalse(isZoomModifierPressed(mouseEvent(0)))
        assertFalse(isZoomModifierPressed(null))
    }

    @Test
    fun `scroll up zooms in and clamps to max`() {
        val zoom = zoomAfterScroll(
            currentZoom = 3.9f,
            scrollDeltaY = -1f,
            minZoom = 0.25f,
            maxZoom = 4f,
            zoomFactor = 1.15f
        )

        assertEquals(4f, zoom, 0.0001f)
    }

    @Test
    fun `scroll down zooms out and clamps to min`() {
        val zoom = zoomAfterScroll(
            currentZoom = 0.26f,
            scrollDeltaY = 1f,
            minZoom = 0.25f,
            maxZoom = 4f,
            zoomFactor = 1.15f
        )

        assertEquals(0.25f, zoom, 0.0001f)
    }

    private fun mouseEvent(modifiers: Int): MouseEvent =
        MouseEvent(Canvas(), MouseEvent.MOUSE_WHEEL, 0L, modifiers, 0, 0, 0, false)
}
