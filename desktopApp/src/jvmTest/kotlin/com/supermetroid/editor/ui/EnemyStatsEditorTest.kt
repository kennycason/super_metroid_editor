package com.supermetroid.editor.ui
import com.supermetroid.editor.rom.TestRomHelper

import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies that all ENEMY_DEFS entries point to valid species headers in the ROM.
 *
 * Species headers in bank $A0 are 64 bytes each, aligned to 0x40-byte boundaries.
 * The two valid grids are:
 *   - Crawler/Hopper/Flyer grid:  address ≡ 0x3F (mod 0x40)  e.g. 0xDCFF
 *   - Spawner/Pirate grid:        address ≡ 0x13 (mod 0x40)  e.g. 0xF193
 *
 * An entry with HP=0 at offset +4 indicates a mid-structure (off-grid) read,
 * which means the speciesId is wrong.
 *
 * Confirmed ROM: Super Metroid (JU) [!].smc — no SMC header (3MB exact).
 */
class EnemyStatsEditorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `named stat entries use their source species headers`() {
        assertEquals(0xD27F, ENEMY_DEFS.single { it.key == "reo" }.speciesId)
        assertEquals(0xD87F, ENEMY_DEFS.single { it.key == "sbug" }.speciesId)
        assertEquals(Triple(0xEABF, 60, 20), ENEMY_DEFS.single { it.key == "kihunter_green" }.let {
            Triple(it.speciesId, it.defaultHp, it.defaultDamage)
        })
        assertEquals(Triple(0xE87F, 60, 10), ENEMY_DEFS.single { it.key == "beetom" }.let {
            Triple(it.speciesId, it.defaultHp, it.defaultDamage)
        })
        assertEquals(Triple(0xE83F, 20, 40), ENEMY_DEFS.single { it.key == "magdollite" }.let {
            Triple(it.speciesId, it.defaultHp, it.defaultDamage)
        })
        assertEquals(ENEMY_DEFS.size, ENEMY_DEFS.map { it.key }.distinct().size)
    }

    // ── All ENEMY_DEFS entries read valid HP (> 0) ───────────────────────────

    @Test
    fun `all ENEMY_DEFS entries read non-zero HP from ROM`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val mismatches = mutableListOf<String>()
        for (e in ENEMY_DEFS) {
            val snesAddr = RomConstants.BANK_ENEMY_AI or e.speciesId
            val pc = parser.snesToPc(snesAddr)
            if (pc + 8 > rom.size) {
                mismatches += "${e.key} (0x${e.speciesId.toString(16)}): PC out of range"
                continue
            }
            val hp = (rom[pc + 4].toInt() and 0xFF) or ((rom[pc + 5].toInt() and 0xFF) shl 8)
            if (hp == 0) {
                mismatches += "${e.key} (0x${e.speciesId.toString(16)}): HP=0 (likely off-grid mid-structure read)"
            }
        }
        assertTrue(mismatches.isEmpty(), "ENEMY_DEFS species IDs with HP=0:\n${mismatches.joinToString("\n")}")
    }

    // ── Spot-check specific vanilla ROM values ────────────────────────────────

    @Test
    fun `Zoomer 0xDCFF reads HP=15 DMG=5`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val pc = parser.snesToPc(RomConstants.BANK_ENEMY_AI or 0xDCFF)
        val hp  = (rom[pc + 4].toInt() and 0xFF) or ((rom[pc + 5].toInt() and 0xFF) shl 8)
        val dmg = (rom[pc + 6].toInt() and 0xFF) or ((rom[pc + 7].toInt() and 0xFF) shl 8)
        assert(hp == 15)  { "Zoomer HP: expected 15, got $hp" }
        assert(dmg == 5)  { "Zoomer DMG: expected 5, got $dmg" }
    }

    @Test
    fun `Geemer horizontal 0xDC3F reads HP=15 DMG=5`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val pc = parser.snesToPc(RomConstants.BANK_ENEMY_AI or 0xDC3F)
        val hp  = (rom[pc + 4].toInt() and 0xFF) or ((rom[pc + 5].toInt() and 0xFF) shl 8)
        val dmg = (rom[pc + 6].toInt() and 0xFF) or ((rom[pc + 7].toInt() and 0xFF) shl 8)
        assert(hp == 15)  { "Geemer H HP: expected 15, got $hp" }
        assert(dmg == 5)  { "Geemer H DMG: expected 5, got $dmg" }
    }

    @Test
    fun `Sidehopper 0xD93F reads HP=60 DMG=20`() {
        val parser = loadTestRom() ?: return
        val rom = parser.getRomData()
        val pc = parser.snesToPc(RomConstants.BANK_ENEMY_AI or 0xD93F)
        val hp  = (rom[pc + 4].toInt() and 0xFF) or ((rom[pc + 5].toInt() and 0xFF) shl 8)
        val dmg = (rom[pc + 6].toInt() and 0xFF) or ((rom[pc + 7].toInt() and 0xFF) shl 8)
        assert(hp == 60)  { "Sidehopper HP: expected 60, got $hp" }
        assert(dmg == 20) { "Sidehopper DMG: expected 20, got $dmg" }
    }
}
