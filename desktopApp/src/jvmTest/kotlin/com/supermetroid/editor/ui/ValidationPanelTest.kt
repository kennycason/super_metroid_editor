package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationPanelTest {
    @Test
    fun `room label places the id before the catalog name`() {
        assertEquals(
            "Room 0xA0D2 · Tourian Escape Room 4",
            validationRoomLabel(0xA0D2, "Tourian Escape Room 4"),
        )
    }

    @Test
    fun `room label does not repeat a generated fallback name`() {
        assertEquals("Room 0x9C5E", validationRoomLabel(0x9C5E, "Room 0x9c5e"))
    }
}
