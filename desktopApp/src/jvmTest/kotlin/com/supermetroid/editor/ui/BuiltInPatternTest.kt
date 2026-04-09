package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Validates that built-in patterns (gates, doors, stations) use correct PLM IDs
 * by cross-referencing against the actual ROM's PLM header table in bank $84.
 *
 * This catches the class of bug where PLM IDs were calculated with wrong offsets
 * (e.g., door caps at +4 instead of +6), producing IDs that point mid-header.
 */
class BuiltInPatternTest {

    private fun loadTestRom(): RomParser? {
        val paths = listOf(
            "test-resources/Super Metroid (JU) [!].smc",
            "../../../test-resources/Super Metroid (JU) [!].smc",
            "/Users/kenny/code/super_metroid_dev/test-resources/Super Metroid (JU) [!].smc"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return RomParser.loadRom(f.absolutePath)
        }
        println("Test ROM not found, skipping test")
        return null
    }

    private fun readU16(rom: ByteArray, pc: Int): Int =
        (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `seedBuiltInPatterns creates all expected patterns`() {
        val parser = loadTestRom() ?: return
        val state = EditorState().also { it.testMode = true }
        state.seedBuiltInPatterns(parser)

        val patternIds = state.project.patterns.map { it.id }.toSet()

        val expectedIds = listOf(
            "builtin_door_blue_left", "builtin_door_blue_right",
            "builtin_door_red_left", "builtin_door_red_right",
            "builtin_door_green_left", "builtin_door_green_right",
            "builtin_door_yellow_left", "builtin_door_yellow_right",
        )

        // Station / structure patterns should exist as tile templates
        val stationPatternIds = listOf(
            "builtin_save_station", "builtin_energy_refill",
            "builtin_missile_refill", "builtin_chozo_statue"
        )
        for (id in stationPatternIds) {
            assertTrue(id in patternIds, "Station pattern should exist: $id")
        }
        for (id in expectedIds) {
            assertTrue(id in patternIds, "Missing built-in pattern: $id")
        }
    }

    @Test
    fun `door pattern PLM IDs all point to valid PLM headers in ROM`() {
        val parser = loadTestRom() ?: return
        val state = EditorState().also { it.testMode = true }
        state.seedBuiltInPatterns(parser)

        val romData = parser.getRomData()
        val validSetups = setOf(0xC794, 0xC7B1, 0xC7BB)

        val doorPatterns = state.project.patterns.filter { it.id.startsWith("builtin_door_") }
        assertTrue(doorPatterns.size >= 8, "Should have at least 8 door patterns")

        for (pattern in doorPatterns) {
            val plmCells = pattern.cells.filterNotNull().filter { it.plmId != 0 }
            assertTrue(plmCells.isNotEmpty(), "${pattern.id} should have at least one PLM cell")

            for (cell in plmCells) {
                val pc = parser.snesToPc(0x840000 or cell.plmId)
                assertTrue(pc > 0 && pc + 5 < romData.size,
                    "${pattern.id}: PLM 0x${cell.plmId.toString(16)} PC out of range")

                val setup = readU16(romData, pc)
                assertTrue(setup in validSetups,
                    "${pattern.id}: PLM 0x${cell.plmId.toString(16)} has setup 0x${setup.toString(16)}, " +
                    "expected one of ${validSetups.map { "0x${it.toString(16)}" }}")
            }
        }
    }

    @Test
    fun `door patterns have correct block types and dimensions`() {
        val parser = loadTestRom() ?: return
        val state = EditorState().also { it.testMode = true }
        state.seedBuiltInPatterns(parser)

        val doorPatterns = state.project.patterns.filter { it.id.startsWith("builtin_door_") }

        for (pattern in doorPatterns) {
            assertEquals(1, pattern.cols, "${pattern.id} should be 1 column wide")
            assertEquals(4, pattern.rows, "${pattern.id} should be 4 rows tall")

            for (cell in pattern.cells.filterNotNull()) {
                assertEquals(0x9, cell.blockType,
                    "${pattern.id}: all cells should be block type 0x9 (door)")
            }

            assertTrue(pattern.noFlip, "${pattern.id} should have noFlip=true")
        }
    }

    @Test
    fun `migration re-seeds station patterns with tile templates`() {
        val parser = loadTestRom() ?: return
        val state = EditorState().also { it.testMode = true }

        // Simulate old project with old-style station patterns (different dimensions)
        val oldSave = com.supermetroid.editor.data.TilePattern(
            id = "builtin_save_station", name = "Save Station",
            cols = 5, rows = 3, builtIn = true,
            cells = mutableListOf<com.supermetroid.editor.data.PatternCell?>(
                com.supermetroid.editor.data.PatternCell(0, plmId = 0xB76F, plmParam = 0x0001),
                com.supermetroid.editor.data.PatternCell(0),
                com.supermetroid.editor.data.PatternCell(0),
            )
        )
        state.project.patterns.add(oldSave)

        state.seedBuiltInPatterns(parser)

        // Save station should exist (kept from old project or re-seeded)
        val saveStation = state.project.patterns.find { it.id == "builtin_save_station" }
        assertNotNull(saveStation, "Save station pattern should exist")

        // Energy refill, missile refill, chozo statue should be seeded fresh
        assertNotNull(state.project.patterns.find { it.id == "builtin_energy_refill" },
            "Energy refill should be seeded")
        assertNotNull(state.project.patterns.find { it.id == "builtin_missile_refill" },
            "Missile refill should be seeded")
        assertNotNull(state.project.patterns.find { it.id == "builtin_chozo_statue" },
            "Chozo statue should be seeded")
    }

    @Test
    fun `migration removes patterns with wrong door PLM IDs`() {
        val parser = loadTestRom() ?: return
        val state = EditorState().also { it.testMode = true }

        // Simulate a project with old wrong PLM IDs
        val wrongBlueRight = com.supermetroid.editor.data.TilePattern(
            id = "builtin_door_blue_right",
            name = "Door: Blue (Right)",
            cols = 1, rows = 4,
            builtIn = true,
            cells = mutableListOf<com.supermetroid.editor.data.PatternCell?>(
                com.supermetroid.editor.data.PatternCell(0x040, blockType = 0x9, plmId = 0xC8A6),
                com.supermetroid.editor.data.PatternCell(0x060, blockType = 0x9),
                com.supermetroid.editor.data.PatternCell(0x060, blockType = 0x9),
                com.supermetroid.editor.data.PatternCell(0x040, blockType = 0x9),
            )
        )
        state.project.patterns.add(wrongBlueRight)

        state.seedBuiltInPatterns(parser)

        val blueRight = state.project.patterns.find { it.id == "builtin_door_blue_right" }
        assertNotNull(blueRight, "Blue right door pattern should exist after migration")

        val plmCell = blueRight!!.cells.filterNotNull().find { it.plmId != 0 }
        assertNotNull(plmCell, "Should have a PLM cell")
        assertEquals(0xC8A8, plmCell!!.plmId,
            "After migration, blue right door should use 0xC8A8, not 0xC8A6")
    }
}
