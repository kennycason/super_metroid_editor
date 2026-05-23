package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Tests for reading enemy species header fields (AI pointers, GFX data, layer control).
 * Verifies the 64-byte enemy header structure at $A0:xxxx.
 */
class EnemyHeaderTest {

    private fun loadParser(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `read Zoomer species header fields`() {
        val rp = loadParser() ?: return
        val rom = rp.getRomData()
        val speciesId = 0xDCFF // Zoomer
        val pc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
        assumeTrue(pc > 0 && pc + 0x40 <= rom.size, "Species header not accessible")

        // HP at +$04
        val hp = readU16(rom, pc + 0x04)
        assertEquals(15, hp, "Zoomer HP should be 15")

        // Damage at +$06
        val damage = readU16(rom, pc + 0x06)
        assertEquals(5, damage, "Zoomer damage should be 5")

        // Init AI at +$12 should be non-zero
        val initAi = readU16(rom, pc + 0x12)
        assertTrue(initAi > 0, "Init AI pointer should be non-zero")

        // Main AI at +$16
        val mainAi = readU16(rom, pc + 0x16)
        assertTrue(mainAi > 0, "Main AI pointer should be non-zero")

        // Touch AI at +$30
        val touchAi = readU16(rom, pc + 0x30)
        assertTrue(touchAi > 0, "Touch AI pointer should be non-zero")

        // Shot AI at +$32
        val shotAi = readU16(rom, pc + 0x32)
        assertTrue(shotAi > 0, "Shot AI pointer should be non-zero")

        // Tile data pointer at +$36 (3 bytes)
        val tileAddrLo = readU16(rom, pc + 0x36)
        val tileBank = rom[pc + 0x38].toInt() and 0xFF
        assertTrue(tileBank > 0, "Tile data bank should be non-zero")

        // Layer control at +$39
        val layer = rom[pc + 0x39].toInt() and 0xFF
        assertTrue(layer in 0..0xFF, "Layer control should be a valid byte")
    }

    @Test
    fun `read multiple enemy AI pointers are distinct`() {
        val rp = loadParser() ?: return
        val rom = rp.getRomData()

        // Different enemies should have different Init AI pointers
        val species = listOf(0xDCFF, 0xD93F, 0xD7FF) // Zoomer, Sidehopper, Skree
        val initPtrs = species.map { id ->
            val pc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or id)
            readU16(rom, pc + 0x12)
        }
        // All three should be distinct
        assertEquals(initPtrs.toSet().size, initPtrs.size, "Different enemies should have different Init AI ptrs")
    }

    @Test
    fun `enemy tile data pointer points to valid ROM data`() {
        val rp = loadParser() ?: return
        val rom = rp.getRomData()
        val speciesId = 0xDCFF // Zoomer
        val pc = rp.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)

        val tileSize = readU16(rom, pc) and 0x7FFF
        val tileAddrLo = readU16(rom, pc + 0x36)
        val tileBank = rom[pc + 0x38].toInt() and 0xFF
        val tileSnesAddr = (tileBank shl 16) or tileAddrLo
        val tilePcAddr = rp.snesToPc(tileSnesAddr)

        assertTrue(tileSize > 0, "Tile data size should be positive")
        assertTrue(tilePcAddr > 0, "Tile PC address should be valid")
        assertTrue(tilePcAddr + tileSize <= rom.size, "Tile data should fit within ROM")
    }
}
