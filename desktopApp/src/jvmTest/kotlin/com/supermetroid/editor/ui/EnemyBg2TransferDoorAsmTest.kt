package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.DoorScrollWrite
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import com.supermetroid.editor.rom.analyzeDoorScrollAsm
import com.supermetroid.editor.rom.buildDoorAsmClearingEnemyBg2Transfer
import com.supermetroid.editor.rom.buildDoorScrollAsm
import com.supermetroid.editor.rom.parseDoorScrollWrites
import com.supermetroid.editor.rom.shouldClearEnemyBg2TransferOnDoor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnemyBg2TransferDoorAsmTest {

    private fun parserWithDoorAsm(entryCode: Int, vararg bytes: Int): RomParser {
        val data = ByteArray(0x300000)
        val parser = RomParser(data)
        val pc = parser.snesToPc(0x8F0000 or entryCode)
        bytes.forEachIndexed { index, value -> data[pc + index] = value.toByte() }
        return parser
    }

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
    fun `retains accumulator across repeated scroll stores`() {
        val parser = parserWithDoorAsm(
            0x9000,
            0x08, 0xE2, 0x20, 0xA9, 0x00,
            0x8F, 0x28, 0xCD, 0x7E,
            0x8F, 0x29, 0xCD, 0x7E,
            0x28, 0x60,
        )

        val analysis = analyzeDoorScrollAsm(parser, 0x9000)

        assertEquals(
            listOf(DoorScrollWrite(0x00, 0x28), DoorScrollWrite(0x00, 0x29)),
            analysis.writes,
        )
        assertTrue(analysis.isPureScrollRoutine)
    }

    @Test
    fun `expands a 16 bit store and refuses to classify mixed door behavior as pure`() {
        val parser = parserWithDoorAsm(
            0x9000,
            0xA9, 0x02, 0x01,             // LDA #$0102 (16-bit entry state)
            0x8F, 0x20, 0xCD, 0x7E,       // STA.l Scrolls
            0xA9, 0x01, 0x00,
            0x22, 0x00, 0x80, 0x80,       // JSL: unrelated behavior
            0x60,
        )

        val analysis = analyzeDoorScrollAsm(parser, 0x9000)

        assertEquals(
            listOf(DoorScrollWrite(0x02, 0x20), DoorScrollWrite(0x01, 0x21)),
            analysis.writes,
        )
        assertFalse(analysis.isPureScrollRoutine)
        assertTrue(analysis.hasOtherEffects)
    }

    @Test
    fun `generated scroll ASM preserves flags and returns with RTS`() {
        val asm = buildDoorScrollAsm(
            listOf(DoorScrollWrite(scrollValue = 0x02, addressLowByte = 0x28))
        ).map { it.toInt() and 0xFF }

        assertEquals(
            listOf(
                0x08,             // PHP
                0xE2, 0x20,       // SEP #$20
                0xA9, 0x02,
                0x8F, 0x28, 0xCD, 0x7E,
                0x28,             // PLP
                0x60,             // RTS
            ),
            asm,
        )
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
