package com.supermetroid.editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RoomCoordinateLabelsTest {
    @Test
    fun `room coordinates are displayed one based from the top left`() {
        assertEquals("(1, 1)", oneBasedRoomCoordinate(0, 0))
        assertEquals("(144, 24)", oneBasedRoomCoordinate(143, 23))
        assertEquals("(1, 5)", roomScreenCoordinateForBlock(0, 72))
        assertEquals("(9, 2)", roomScreenCoordinateForBlock(143, 23))
    }
}
