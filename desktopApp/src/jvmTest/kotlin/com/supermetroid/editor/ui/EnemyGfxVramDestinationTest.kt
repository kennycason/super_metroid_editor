package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnemyGfxVramDestinationTest {

    @Test
    fun `selects vanilla-compatible destination instead of row 8`() {
        val landingSiteEntries = listOf(
            RomParser.EnemyGfxEntry(0xD07F, 2),
            RomParser.EnemyGfxEntry(0xD0BF, 7),
        )

        val selected = selectEnemyGfxVramDestination(
            existingEntries = landingSiteEntries,
            vanillaDestinations = listOf(3, 1),
        )

        assertEquals(3, selected)
        val selectedValue = selected ?: error("Expected a destination")
        assertTrue((selectedValue and 0xFF) in 1..7)
        assertFalse((selectedValue and 0xFF) == 8)
    }

    @Test
    fun `falls back to an unused normal destination when species has no vanilla entry`() {
        val selected = selectEnemyGfxVramDestination(
            existingEntries = listOf(RomParser.EnemyGfxEntry(0xD07F, 2)),
            vanillaDestinations = emptyList(),
        )

        assertEquals(1, selected)
    }

    @Test
    fun `vanilla scan finds Mella gfx destinations`() {
        val romParser = TestRomHelper.loadRomParser() ?: return

        val destinations = collectVanillaEnemyGfxDestinations(romParser)[0xD13F].orEmpty()

        assertTrue(3 in destinations, "Mella should use destination 3 in vanilla room 0xA815")
        assertTrue(1 in destinations, "Mella should use destination 1 in vanilla room 0xAB8F")
    }
}
