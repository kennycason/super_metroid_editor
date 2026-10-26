package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import org.junit.jupiter.api.Test
import kotlin.test.*

class MusicRomTransferChainTest {
    private fun word(rom: ByteArray, at: Int, value: Int) {
        rom[at] = value.toByte(); rom[at + 1] = (value ushr 8).toByte()
    }

    @Test fun `inventory includes headers overlapping data and the full native terminator`() {
        val rom = ByteArray(0x10000)
        val parser = RomParser(rom)
        val start = parser.snesToPc(0x80FFFA)
        word(rom, start, 4); word(rom, start + 2, 0x6000)
        byteArrayOf(1, 2, 3, 4).copyInto(rom, start + 4)
        word(rom, start + 8, 2); word(rom, start + 10, 0x6002)
        byteArrayOf(5, 6).copyInto(rom, start + 12)
        word(rom, start + 14, 0); word(rom, start + 16, 0x1500)
        val source = MusicRomTransferChain.read(parser, 0x80FFFA)
        assertEquals(start until start + 18, source.romRange)
        assertEquals(0x1500, source.entryAddress)
        val ram = ByteArray(65536).also { SpcData.applyTransferBlocks(it, source.blocks) }
        assertContentEquals(byteArrayOf(1, 2, 5, 6), ram.copyOfRange(0x6000, 0x6004))
        assertTrue(source.covers(mapOf(0x6000 to byteArrayOf(9, 8, 7, 6))))
        assertFalse(source.covers(mapOf(0x6003 to byteArrayOf(9, 8))))
        assertFalse(source.covers(mapOf(-1 to byteArrayOf(9))))
        assertFalse(source.covers(mapOf(0xFFFF to byteArrayOf(9, 8))))
    }

    @Test fun `coverage must include every requested byte not just its start`() {
        val chain = MusicRomTransferChain(0..20, listOf(
            SpcData.TransferBlock(0x6000, byteArrayOf(1, 2)),
            SpcData.TransferBlock(0x6003, byteArrayOf(3, 4))), 0x1500)
        assertTrue(chain.covers(mapOf(0x6000 to byteArrayOf(1, 2), 0x6003 to byteArrayOf(3, 4))))
        assertFalse(chain.covers(mapOf(0x6000 to byteArrayOf(1, 2, 3, 4))))
        // Equal-to-baseline bytes still need an actual upload to restore retained RAM.
        assertFalse(chain.covers(mapOf(0x6CA8 to ByteArray(6))))
    }

    @Test fun `truncated blocks and terminators fail instead of inventorying a partial source`() {
        for (size in listOf(8, 10)) {
            val rom = ByteArray(size)
            word(rom, 0, 4); word(rom, 2, 0x6000)
            val error = assertFailsWith<IllegalArgumentException> { MusicRomTransferChain.read(RomParser(rom), 0x808000) }
            assertTrue(error.message.orEmpty().contains("complete terminator"))
        }
        val tooLong = ByteArray(8).also { word(it, 0, 9); word(it, 2, 0x6000) }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            MusicRomTransferChain.read(RomParser(tooLong), 0x808000)
        }.message.orEmpty().contains("ROM bounds"))
    }

    @Test fun `invalid native destinations and ROM pointers fail before mapping bytes`() {
        val rom = ByteArray(16).also { word(it, 0, 2); word(it, 2, 0xFFFF) }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            MusicRomTransferChain.read(RomParser(rom), 0x808000)
        }.message.orEmpty().contains("SPC RAM"))
        for (pointer in listOf(0, 0x807FFF, 0x7F8000, 0x1008000, 0x818000)) {
            assertFailsWith<IllegalArgumentException> { MusicRomTransferChain.read(RomParser(rom), pointer) }
        }
    }

    @Test fun `serialized budget reserves both terminator words at the exact bank limit`() {
        val max = MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES
        val blocks = listOf(SpcData.TransferBlock(0x6000, ByteArray(max - 8)))
        assertEquals(max, MusicTransferChainBudget.serializedTransferChainSize(blocks))
        assertEquals(4, MusicTransferChainBudget.serializedTransferChainSize(emptyList()))
    }
}
