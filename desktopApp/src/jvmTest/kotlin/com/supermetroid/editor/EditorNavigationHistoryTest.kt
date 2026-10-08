package com.supermetroid.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorNavigationHistoryTest {
    @Test
    fun `back and forward preserve browser order`() {
        val history = EditorNavigationHistory<String>()

        history.recordDeparture("Rooms: Landing Site")
        history.recordDeparture("ASM: bank 8F line 200")

        assertEquals("ASM: bank 8F line 200", history.goBack("Rooms: Parlor"))
        assertEquals("Rooms: Landing Site", history.goBack("ASM: bank 8F line 200"))
        assertFalse(history.canGoBack)
        assertTrue(history.canGoForward)

        assertEquals("ASM: bank 8F line 200", history.goForward("Rooms: Landing Site"))
        assertEquals("Rooms: Parlor", history.goForward("ASM: bank 8F line 200"))
        assertFalse(history.canGoForward)
    }

    @Test
    fun `new navigation clears the forward trail and coalesces duplicate departures`() {
        val history = EditorNavigationHistory<String>()

        history.recordDeparture("Rooms")
        history.recordDeparture("Rooms")
        assertEquals("Rooms", history.goBack("ASM"))
        assertNull(history.goBack("Rooms"))

        history.recordDeparture("Tiles")
        assertFalse(history.canGoForward)
        assertEquals("Tiles", history.goBack("Sprites"))
    }

    @Test
    fun `history is bounded`() {
        val history = EditorNavigationHistory<Int>(maxEntries = 2)

        history.recordDeparture(1)
        history.recordDeparture(2)
        history.recordDeparture(3)

        assertEquals(3, history.goBack(4))
        assertEquals(2, history.goBack(3))
        assertNull(history.goBack(2))
    }
}
