package com.supermetroid.editor.ui

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointerDragInputTest {

    @Test
    fun `primary pointer press starts pixel drawing`() {
        assertTrue(isPrimaryPointerPress(mousePressed(MouseEvent.BUTTON1)))
        assertFalse(isPrimaryPointerPress(mousePressed(MouseEvent.BUTTON2)))
        assertFalse(isPrimaryPointerPress(mousePressed(MouseEvent.BUTTON3)))
    }

    @Test
    fun `unknown native press preserves compose fallback behavior`() {
        assertTrue(isPrimaryPointerPress(null))
    }

    @Test
    fun `move continues drawing only while primary button remains down`() {
        assertTrue(isPrimaryPointerStillDown(mouseDragged(InputEvent.BUTTON1_DOWN_MASK)))
        assertFalse(isPrimaryPointerStillDown(mouseDragged(0)))
    }

    @Test
    fun `unknown native move preserves active drag state`() {
        assertTrue(isPrimaryPointerStillDown(null))
    }

    private fun mousePressed(button: Int): MouseEvent =
        MouseEvent(Canvas(), MouseEvent.MOUSE_PRESSED, 0L, 0, 0, 0, 1, false, button)

    private fun mouseDragged(modifiers: Int): MouseEvent =
        MouseEvent(Canvas(), MouseEvent.MOUSE_DRAGGED, 0L, modifiers, 0, 0, 0, false, MouseEvent.NOBUTTON)
}
