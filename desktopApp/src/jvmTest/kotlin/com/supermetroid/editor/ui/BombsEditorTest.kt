package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BombsEditorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readByte(parser: RomParser, pc: Int): Int {
        val rom = parser.getRomData()
        return rom[pc].toInt() and 0xFF
    }

    private fun readWord(parser: RomParser, pc: Int): Int {
        val rom = parser.getRomData()
        return (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `bomb fuse timer address reads vanilla 60 frames`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x003C, readWord(parser, BOMB_FUSE_TIMER_PC))
    }

    @Test
    fun `bomb hard cap compare operand reads vanilla five slots`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x0005, readWord(parser, BOMB_ACTIVE_HARD_CAP_OPERAND_PC))
    }

    @Test
    fun `bomb cooldown address reads vanilla 16 frames`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x10, readByte(parser, BOMB_COOLDOWN_PC))
    }

    @Test
    fun `bomb explosion frame delay operand reads vanilla one frame`() {
        val parser = loadTestRom() ?: return
        assertEquals(0x0001, readWord(parser, BOMB_EXPLOSION_FRAME_DELAY_OPERAND_PC))
    }

    @Test
    fun `readBombsRomDefaults derives stock practical max of three bombs`() {
        val parser = loadTestRom() ?: return
        val defaults = readBombsRomDefaults(parser)
        assertEquals(3, defaults.maxActiveBombs)
        assertEquals(0x003C, defaults.fuseFrames)
        assertEquals(0x10, defaults.cooldownFrames)
        assertEquals(0x0001, defaults.explosionFrameDelay)
        assertEquals(0x0005, defaults.hardCap)
    }

    @Test
    fun `calculateBombCooldownForConfig preserves stock cooldown for three or fewer bombs`() {
        assertEquals(0x10, calculateBombCooldownForConfig(maxActiveBombs = 1, fuseFrames = 60, baseCooldownFrames = 0x10))
        assertEquals(0x10, calculateBombCooldownForConfig(maxActiveBombs = 2, fuseFrames = 60, baseCooldownFrames = 0x10))
        assertEquals(0x10, calculateBombCooldownForConfig(maxActiveBombs = 3, fuseFrames = 60, baseCooldownFrames = 0x10))
    }

    @Test
    fun `calculateBombCooldownForConfig lowers cooldown for four and five bombs with stock fuse`() {
        assertEquals(14, calculateBombCooldownForConfig(maxActiveBombs = 4, fuseFrames = 60, baseCooldownFrames = 0x10))
        assertEquals(11, calculateBombCooldownForConfig(maxActiveBombs = 5, fuseFrames = 60, baseCooldownFrames = 0x10))
    }

    @Test
    fun `calculateBombCooldownForConfig allows zero cooldown`() {
        assertEquals(0, calculateBombCooldownForConfig(maxActiveBombs = 5, fuseFrames = 60, baseCooldownFrames = 0))
    }

    @Test
    fun `derivePracticalBombCap uses cooldown and hard cap`() {
        assertEquals(5, derivePracticalBombCap(fuseFrames = 60, cooldownFrames = 0, hardCap = 5))
        assertEquals(3, derivePracticalBombCap(fuseFrames = 60, cooldownFrames = 16, hardCap = 5))
        assertEquals(2, derivePracticalBombCap(fuseFrames = 60, cooldownFrames = 16, hardCap = 2))
    }
}
