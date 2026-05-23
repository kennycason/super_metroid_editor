package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests for reading save station spawn data from the AreaSave table.
 * The table has 8 area pointers at PC $44B5, each pointing to N save entries (14 bytes each).
 */
class SaveStationTest {

    @Test
    fun `read Crateria save entry 0`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        val entry = rp.readSaveEntry(0, 0)
        assertNotNull(entry, "Should be able to read Crateria save entry 0")

        // Crateria save 0 should point to a valid room
        assertTrue(entry!!.roomId > 0, "Room ID should be non-zero")
        assertTrue(entry.samusX > 0 || entry.samusY > 0, "Samus position should be non-zero")
    }

    @Test
    fun `read save entries for all areas`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        for (area in 0..6) { // Areas 0-6 (Crateria through Ceres)
            val entry = rp.readSaveEntry(area, 0)
            assertNotNull(entry, "Area $area should have at least one save entry")
            assertTrue(entry!!.roomId > 0, "Area $area save 0: Room ID should be non-zero")
        }
    }

    @Test
    fun `save entry has valid spawn coordinates`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        // Crateria has multiple save stations
        val entry = rp.readSaveEntry(0, 0)
        assertNotNull(entry)

        // Scroll positions are in pixels (multiples of 256 for screen boundaries)
        assertTrue(entry!!.scrollX >= 0, "Scroll X should be non-negative")
        assertTrue(entry.scrollY >= 0, "Scroll Y should be non-negative")

        // Samus position is relative within the room
        assertTrue(entry.samusY in 0..0xFFFF, "Samus Y should be valid 16-bit")
        assertTrue(entry.samusX in 0..0xFFFF, "Samus X should be valid 16-bit")
    }

    @Test
    fun `save entry has valid door pointer`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        val entry = rp.readSaveEntry(0, 0)
        assertNotNull(entry)
        assertTrue(entry!!.doorPtr > 0, "Door pointer should be non-zero")

        // The door pointer should be a valid offset within bank $83
        // (door data bank)
        val doorPc = rp.snesToPc(0x830000 or entry.doorPtr)
        assertTrue(doorPc > 0, "Door PC address should be valid")
    }

    @Test
    fun `different areas have different save room IDs`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        val roomIds = (0..4).mapNotNull { area ->
            rp.readSaveEntry(area, 0)?.roomId
        }
        // At least some areas should have different room IDs
        assertTrue(roomIds.toSet().size > 1, "Different areas should save to different rooms")
    }
}
