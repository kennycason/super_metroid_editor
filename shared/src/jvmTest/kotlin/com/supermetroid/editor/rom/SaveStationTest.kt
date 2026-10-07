package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests for reading runtime load-station data (often called AreaSave by tools).
 * The table has 8 area pointers at PC $44B5, each pointing to N 14-byte entries.
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
    fun `save entry reads are bounded to each area table`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        for (area in 0..6) {
            val count = rp.saveEntryCount(area)
            assertTrue(count > 0, "Area $area should expose at least one save entry")
            assertNotNull(rp.readSaveEntry(area, count - 1), "Area $area last save entry should be readable")
            assertNull(rp.readSaveEntry(area, count), "Area $area should not read into the next area table")
        }
    }

    @Test
    fun `all retail load station list sizes include the final debug entry`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        assertEquals(listOf(19, 19, 23, 18, 20, 18, 17, 17), (0..7).map(rp::saveEntryCount))
        assertEquals(0x80CC19, rp.readLoadStationListAddress(7))
        assertNotNull(rp.readSaveEntry(7, 16), "The seventeenth debug entry must not be truncated")
        assertNull(rp.readSaveEntry(7, 17))
    }

    @Test
    fun `load station parser preserves the demo recorder door BTS field`() {
        val rp = TestRomHelper.loadRomParser() ?: return

        assertEquals(1, rp.readSaveEntry(1, 8)?.doorBts)
        assertEquals(0, rp.readSaveEntry(1, 0)?.doorBts)
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
