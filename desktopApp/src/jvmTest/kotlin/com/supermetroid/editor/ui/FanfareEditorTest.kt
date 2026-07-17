package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FanfareEditorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    private fun readWord(parser: RomParser, pc: Int): Int {
        val rom = parser.getRomData()
        return (rom[pc].toInt() and 0xFF) or ((rom[pc + 1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `item message box fanfare wait reads vanilla 360 frames`() {
        val parser = loadTestRom() ?: return
        assertEquals(FANFARE_DEFAULT_FRAMES, readWord(parser, FANFARE_MESSAGE_BOX_WAIT_PC))
    }

    @Test
    fun `item pickup room music resume delays read vanilla 360 frames`() {
        val parser = loadTestRom() ?: return
        for (pc in FANFARE_MUSIC_RESUME_DELAY_PCS) {
            assertEquals(FANFARE_DEFAULT_FRAMES, readWord(parser, pc), "PC ${pc.toString(16)}")
        }
    }

    @Test
    fun `readFanfareRomDefaults reads vanilla values`() {
        val parser = loadTestRom() ?: return
        val defaults = readFanfareRomDefaults(parser)
        assertEquals(FANFARE_DEFAULT_FRAMES, defaults.itemFanfareFrames)
        assertEquals(FANFARE_DEFAULT_FRAMES, defaults.roomMusicResumeFrames)
    }
}
