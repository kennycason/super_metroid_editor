package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnemyBg2TransferDoorAsmTest {

    @Test
    fun `only doors leaving Mother Brain room need stale BG2 transfer cleanup`() {
        assertTrue(shouldClearEnemyBg2TransferOnDoor(0xDD58, 0x91F8))
        assertFalse(shouldClearEnemyBg2TransferOnDoor(0xDD58, 0xDD58))
        assertFalse(shouldClearEnemyBg2TransferOnDoor(0x92FD, 0xDE7A))
    }

    @Test
    fun `parses vanilla Landing Site arrival scroll write`() {
        val romParser = TestRomHelper.loadRomParser() ?: return

        val writes = parseDoorScrollWrites(romParser, 0xB997)

        assertEquals(listOf(DoorScrollWrite(scrollValue = 0x01, addressLowByte = 0x33)), writes)
    }

    @Test
    fun `generated cleanup ASM clears BG2 transfer state preserves scroll writes and returns with RTS`() {
        val asm = buildDoorAsmClearingEnemyBg2Transfer(
            listOf(DoorScrollWrite(scrollValue = 0x01, addressLowByte = 0x33))
        ).map { it.toInt() and 0xFF }

        assertEquals(
            listOf(
                0x08,             // PHP
                0xC2, 0x20,       // REP #$20
                0xA9, 0x00, 0x00, // LDA #$0000
                0x8F, 0x1E, 0x0E, 0x7E, // STA $7E0E1E
                0x8F, 0x9A, 0x17, 0x7E, // STA $7E179A
                0xE2, 0x20,       // SEP #$20
                0xA9, 0x01,       // LDA #$01
                0x8F, 0x33, 0xCD, 0x7E, // STA $7ECD33
                0x28,             // PLP
                0x60,             // RTS
            ),
            asm,
        )
    }
}
