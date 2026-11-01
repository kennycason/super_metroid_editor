package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class SpcMusicBankTableTest {
    @Test fun `expanded table retains native pointers and uses explicit zero entries without vanilla fallback`() {
        val rom = TestRomHelper.requireRomBytes().copyOf()
        val parser = RomParser(rom)
        val entries = SpcData.TRANSFER_SONG_SETS.associateWith { SpcData.readSongSetPointer(parser, it) }.toMutableMap()
        entries[0xFC] = entries.getValue(3)
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
        val allocation = assertNotNull(allocator.allocate(SpcMusicBankTable.data(entries), listOf(0xB8), "test music table", 16))
        SpcMusicBankTable.patchWrites(parser, allocation.snesAddress + SpcMusicBankTable.HEADER_BYTES).forEach { (at, data) -> data.copyInto(rom, at) }
        val exported = RomParser(rom)
        assertTrue(SpcMusicBankTable.read(exported).expanded)
        assertEquals(entries.keys, SpcMusicBankTable.songSets(exported).toSet())
        for ((set, pointer) in entries) assertEquals(pointer, SpcData.readSongSetPointer(exported, set))
        assertEquals(0, SpcData.readSongSetPointer(exported, 0x4B))
        val table = SpcMusicBankTable.read(exported)
        rom.fill(0, table.pc + 3, table.pc + 6)
        assertEquals(0, SpcData.readSongSetPointer(RomParser(rom), 3), "Owned tables must not mask a corrupt native entry with a vanilla fallback")
    }
    @Test fun `bank table extension refuses changed queue opcodes before producing patches`() {
        val parser = TestRomHelper.requireRomParser()
        val rom = parser.copyRomData().also { it[parser.snesToPc(0x808F72)] = 0xEA.toByte() }
        assertFailsWith<IllegalArgumentException> { SpcMusicBankTable.patchWrites(RomParser(rom), 0xB88008) }
    }
}
