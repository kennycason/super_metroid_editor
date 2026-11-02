package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class SpcTransferStreamTest {
    private fun fixture(header: Boolean = false): Pair<ByteArray, RomParser> {
        val loaded = TestRomHelper.requireRomParser().copyRomData()
        val raw = if (loaded.size % 0x8000 == 512) loaded.copyOfRange(512, loaded.size) else loaded
        val rom = if (header) ByteArray(raw.size + 512).also { raw.copyInto(it, 512) } else raw
        val parser = RomParser(rom)
        SpcTransferStream.loaderCode.copyInto(rom, parser.snesToPc(0xB88000))
        byteArrayOf(0x22, 0, 0x80.toByte(), 0xB8.toByte(), 0xEA.toByte(), 0xEA.toByte())
            .copyInto(rom, parser.snesToPc(SpcTransferStream.PATCH_SNES))
        return rom to parser
    }
    private fun pointer(rom: ByteArray, pc: Int, value: Int) { repeat(3) { rom[pc + it] = (value ushr (it * 8)).toByte() } }
    private fun jump(pointer: Int) = byteArrayOf(-1, -1, pointer.toByte(), (pointer ushr 8).toByte(), (pointer ushr 16).toByte())
    private fun entry(at: Int, data: ByteArray) = byteArrayOf(data.size.toByte(), (data.size ushr 8).toByte(), at.toByte(), (at ushr 8).toByte()) + data
    private val end = byteArrayOf(0, 0, 0, 0x15)

    @Test fun `discontiguous streams retain ordered blocks exact physical ownership and native entry`() {
        for (header in listOf(false, true)) {
            val (rom, parser) = fixture(header)
            val first = entry(0x6C00, byteArrayOf(1, 2, 3, 4, 5, 6)) + jump(0xB98000)
            val last = entry(0x6C00, byteArrayOf(7, 8, 9, 10, 11, 12)) + end
            first.copyInto(rom, parser.snesToPc(0xB89000)); last.copyInto(rom, parser.snesToPc(0xB98000))
            val stream = SpcTransferStream.read(parser, 0xB89000)
            assertEquals(listOf(parser.snesToPc(0xB89000) until parser.snesToPc(0xB89000) + first.size,
                parser.snesToPc(0xB98000) until parser.snesToPc(0xB98000) + last.size), stream.romRanges)
            assertEquals(0x1500, stream.entryAddress)
            val ram = ByteArray(65536).also { SpcData.applyTransferBlocks(it, stream.blocks) }
            assertContentEquals(byteArrayOf(7, 8, 9, 10, 11, 12), ram.copyOfRange(0x6C00, 0x6C06))
            val table = parser.snesToPc(0x8FE7E1) + 3
            pointer(rom, table, 0xB89000)
            assertContentEquals(ram.copyOfRange(0x6C00, 0x6C06), SpcData.findSongSetTransferData(parser, 3).last().data)
            val writes = SpcData.buildRomWritesForSpcRamWrites(parser, 3, mapOf(0x6C03 to byteArrayOf(42)))
            assertEquals(parser.snesToPc(0xB98000) + 7, writes.single().first)
        }
    }

    @Test fun `direct and indirect cycles and jumps into earlier payload are rejected`() {
        for (indirect in listOf(false, true)) {
            val (rom, parser) = fixture()
            jump(if (indirect) 0xB98000 else 0xB89000).copyInto(rom, parser.snesToPc(0xB89000))
            if (indirect) jump(0xB89000).copyInto(rom, parser.snesToPc(0xB98000))
            assertTrue(assertFailsWith<IllegalArgumentException> { SpcTransferStream.read(parser, 0xB89000) }.message.orEmpty().contains("cyclic"))
        }
        val (rom, parser) = fixture()
        (entry(0x6C00, byteArrayOf(1, 0, 0, 0x60, 7, 8)) + jump(0xB89004)).copyInto(rom, parser.snesToPc(0xB89000))
        assertFailsWith<IllegalArgumentException> { SpcTransferStream.read(parser, 0xB89000) }
    }

    @Test fun `unknown or corrupted hook cannot authorize a continuation`() {
        for (target in listOf(0x8080B1, 0x8080C4, 0xB88000)) {
            val (rom, parser) = fixture()
            (entry(0x6C00, ByteArray(6)) + jump(0xB98000)).copyInto(rom, parser.snesToPc(0xB89000))
            end.copyInto(rom, parser.snesToPc(0xB98000))
            val at = parser.snesToPc(target)
            rom[at] = (rom[at].toInt() xor 1).toByte()
            assertFailsWith<IllegalArgumentException> { SpcTransferStream.read(parser, 0xB89000) }
        }
    }

    @Test fun `jump target bounds bank markers and bounded hop count reject malformed streams`() {
        for (target in listOf(0, 0x7F8000, 0xB87FFF, 0xB8FFFF, 0xFF8000)) {
            val (rom, parser) = fixture()
            jump(target).copyInto(rom, parser.snesToPc(0xB89000))
            assertFailsWith<IllegalArgumentException>(target.toString(16)) { SpcTransferStream.read(parser, 0xB89000) }
        }
        val (rom, parser) = fixture()
        repeat(66) { index -> jump(0xB89000 + (index + 1) * 16).copyInto(rom, parser.snesToPc(0xB89000 + index * 16)) }
        assertTrue(assertFailsWith<IllegalArgumentException> { SpcTransferStream.read(parser, 0xB89000) }.message.orEmpty().contains("too many continuations"))
        jump(0xB98000).copyInto(rom, parser.snesToPc(0xB8FFFC))
        assertFailsWith<IllegalArgumentException> { SpcTransferStream.read(parser, 0xB8FFFC) }
    }

    @Test fun `native word aligned header overflow remains supported`() {
        val (rom, parser) = fixture()
        (entry(0x6000, byteArrayOf(1, 2)) + end).copyInto(rom, parser.snesToPc(0xB8FFFE))
        val stream = SpcTransferStream.read(parser, 0xB8FFFE)
        assertEquals(0x6000, stream.blocks.single().destAddr)
        assertContentEquals(byteArrayOf(1, 2), stream.blocks.single().data)
    }

    @Test fun `complete installed loader profile is recognized while unrelated ROM bytes are not reinterpreted`() {
        val (rom, parser) = fixture()
        assertEquals(0xB88000, SpcTransferStream.installedCodePointer(parser))
        SpcTransferStream.requireSupportedLoader(parser)
        assertFailsWith<IllegalArgumentException> { SpcTransferStream.requireOriginalLoader(parser) }
        val body = parser.snesToPc(0xB88000)
        rom[body + 42] = 0
        assertFailsWith<IllegalArgumentException> { SpcTransferStream.installedCodePointer(parser) }
    }
}
