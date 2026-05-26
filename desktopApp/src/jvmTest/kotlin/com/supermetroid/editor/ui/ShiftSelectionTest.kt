package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShiftSelectionTest {

    @Test
    fun `shift selection right by 1 moves tiles`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return // Landing Site
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        // Read tile at (5, 5)
        val origWord = es.readBlockWord(5, 5)
        val origBts = es.readBts(5, 5)

        // Set selection around (5, 5) - single tile
        es.mapSelStart = 5 to 5
        es.mapSelEnd = 5 to 5

        // Shift right by 1
        es.shiftSelection(1, 0)

        // Tile at (5, 5) should be cleared
        assertEquals(RomConstants.AIR_TILE_WORD, es.readBlockWord(5, 5), "Source should be air tile")
        assertEquals(0, es.readBts(5, 5), "Source BTS should be cleared")

        // Tile at (6, 5) should have the original data
        assertEquals(origWord, es.readBlockWord(6, 5), "Destination should have original tile")
        assertEquals(origBts, es.readBts(6, 5), "Destination should have original BTS")

        // Selection should have moved
        assertEquals(6 to 5, es.mapSelStart)
        assertEquals(6 to 5, es.mapSelEnd)
    }

    @Test
    fun `shift 2x2 selection down moves all tiles`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        // Read 2x2 block at (10, 10)
        val words = Array(2) { y -> IntArray(2) { x -> es.readBlockWord(10 + x, 10 + y) } }

        es.mapSelStart = 10 to 10
        es.mapSelEnd = 11 to 11

        es.shiftSelection(0, 1)

        // Top row (y=10) should be cleared — it was source-only, not destination
        for (x in 0..1) {
            assertEquals(RomConstants.AIR_TILE_WORD, es.readBlockWord(10 + x, 10), "Row 10 should be air tile")
        }
        // Rows 11-12 should have the shifted data
        for (y in 0..1) for (x in 0..1) {
            assertEquals(words[y][x], es.readBlockWord(10 + x, 11 + y),
                "Tile at (${10+x}, ${11+y}) should have data from (${10+x}, ${10+y})")
        }

        // Selection moved down
        assertEquals(10 to 11, es.mapSelStart)
        assertEquals(11 to 12, es.mapSelEnd)
    }

    @Test
    fun `shift is undoable`() {
        val rp = TestRomHelper.loadRomParser() ?: return
        val room = rp.readRoomHeader(0x91F8) ?: return
        val es = EditorState()
        es.loadRoom(0x91F8, rp, room)

        val origWord = es.readBlockWord(5, 5)
        es.mapSelStart = 5 to 5
        es.mapSelEnd = 5 to 5
        es.shiftSelection(1, 0)

        // After shift: (5,5) is air, (6,5) has original
        assertEquals(RomConstants.AIR_TILE_WORD, es.readBlockWord(5, 5))

        // Undo
        es.undo()
        assertEquals(origWord, es.readBlockWord(5, 5), "Undo should restore original tile")
    }
}
