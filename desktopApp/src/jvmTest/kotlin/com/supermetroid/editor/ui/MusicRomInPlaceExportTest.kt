package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.*

class MusicRomInPlaceExportTest {
    private fun bankRam(parser: RomParser, songSet: Int) = SpcData.buildInitialSpcRam(parser).also {
        SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
    }

    private fun edit(parser: RomParser, project: SmEditProject, songSet: Int, slot: Int = 24) {
        val original = NspcRenderer.readInstrumentTable(bankRam(parser, songSet))[slot]
        val entry = original.copy(srcn = 0x85, adsr1 = 0x8F, adsr2 = 0xA1, gain = original.gain xor 1, pitchAdj = 0x1234)
        project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)] = MusicInstrumentBankEdit(
            mutableMapOf(slot to MusicInstrumentBanks.entry(entry)))
    }

    private fun noSpace(parser: RomParser, rom: ByteArray) = RomFreeSpaceAllocator(
        rom, parser::snesToPc, parser::pcToSnes, excludedRanges = listOf(rom.indices))

    private fun pointer(rom: ByteArray, at: Int, value: Int) {
        repeat(3) { rom[at + it] = (value ushr (it * 8)).toByte() }
    }

    @Test fun `all native banks can edit an existing sound without allocating or repointing any chain`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc")
        val sets = SpcData.TRANSFER_SONG_SETS.filter { it > 0 }
        for (songSet in sets) edit(parser, project, songSet)
        val report = MusicBankTransitions.inspect(parser, project)
        assertTrue(report.canExport, report.issues.take(8).joinToString())
        assertTrue(report.instrumentRestorations.isEmpty())
        val before = parser.copyRomData()
        val rom = before.copyOf()
        assertEquals(sets.size, MusicRomExporter(project, {}).applyMusicEditsToRom(rom, noSpace(parser, rom)))
        val exported = RomParser(rom)
        val expectedPatches = sortedMapOf<Int, Byte>()
        for (songSet in sets) {
            assertEquals(SpcData.readSongSetPointer(parser, songSet), SpcData.readSongSetPointer(exported, songSet))
            assertContentEquals(MusicBankResolver.resolve(parser, project, songSet).ram, bankRam(exported, songSet))
            for ((at, data) in SpcData.buildRomWritesForSpcRamWrites(parser, songSet,
                MusicInstrumentBanks.writes(MusicInstrumentBanks.instruments(project, songSet)))) {
                data.forEachIndexed { i, byte -> if (byte != before[at + i]) expectedPatches[at + i] = byte }
            }
        }
        assertTrue(expectedPatches.isNotEmpty())
        assertEquals(expectedPatches, rom.indices.filter { before[it] != rom[it] }.associateWith { rom[it] },
            "Only the selected instruments' final upload bytes may change, not headers, base sounds, sequences or pointers")
    }

    @Test fun `headered ROM mapping preserves the copier header and original bank bytes`() {
        val original = TestRomHelper.requireRomParser().copyRomData()
        val unheadered = if (original.size % 0x8000 == 512) original.copyOfRange(512, original.size) else original
        val rom = ByteArray(unheadered.size + 512) { 0x5A }.also { unheadered.copyInto(it, 512) }
        val parser = RomParser(rom)
        val project = SmEditProject(romPath = "headered.smc").also { edit(parser, it, 3) }
        val expected = MusicBankResolver.resolve(parser, project, 3).ram
        val beforeHeader = rom.copyOfRange(0, 512)
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom, noSpace(parser, rom)))
        assertContentEquals(beforeHeader, rom.copyOfRange(0, 512))
        assertContentEquals(expected, bankRam(RomParser(rom), 3))
    }

    @Test fun `aliased bank pointers force isolated relocation instead of changing another bank`() {
        for (aliasSet in listOf(0x0F, 0x48)) {
            val rom = TestRomHelper.requireRomParser().copyRomData()
            var parser = RomParser(rom)
            val originalPointer = SpcData.readSongSetPointer(parser, 3)
            pointer(rom, SpcData.findSongSetPointerEntryPc(parser, aliasSet), originalPointer)
            parser = RomParser(rom)
            val project = SmEditProject(romPath = "alias.sfc").also { edit(parser, it, 3) }
            val aliasRam = bankRam(parser, aliasSet)
            val source = MusicRomTransferChain.read(parser, originalPointer)
            val sourceBytes = rom.sliceArray(source.romRange)
            val before = rom.copyOf()
            assertFailsWith<IllegalStateException> {
                MusicRomExporter(project, {}).applyMusicEditsToRom(rom, noSpace(parser, rom))
            }
            assertContentEquals(before, rom, "An aliased bank needs its own space before any edit is written")
            assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
            val exported = RomParser(rom)
            assertNotEquals(originalPointer, SpcData.readSongSetPointer(exported, 3))
            assertEquals(originalPointer, SpcData.readSongSetPointer(exported, aliasSet))
            assertContentEquals(sourceBytes, rom.sliceArray(source.romRange))
            assertContentEquals(aliasRam, bankRam(exported, aliasSet))
            assertContentEquals(MusicBankResolver.resolve(parser, project, 3).ram, bankRam(exported, 3))
        }
    }

    @Test fun `a bank pointing into another chain is protected even when the start pointers differ`() {
        val rom = TestRomHelper.requireRomParser().copyRomData()
        var parser = RomParser(rom)
        val sourcePointer = SpcData.readSongSetPointer(parser, 3)
        val source = MusicRomTransferChain.read(parser, sourcePointer)
        val suffixPc = source.romRange.first + source.blocks.takeWhile { it.destAddr != 0x6C90 }.sumOf { 4 + it.data.size }
        val suffixPointer = parser.pcToSnes(suffixPc)
        pointer(rom, SpcData.findSongSetPointerEntryPc(parser, 0x48), suffixPointer)
        parser = RomParser(rom)
        val beforeSuffix = bankRam(parser, 0x48)
        val project = SmEditProject(romPath = "suffix.sfc").also { edit(parser, it, 3) }
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        assertNotEquals(sourcePointer, SpcData.readSongSetPointer(exported, 3))
        assertEquals(suffixPointer, SpcData.readSongSetPointer(exported, 0x48))
        assertContentEquals(beforeSuffix, bankRam(exported, 0x48))
    }

    @Test fun `mixed in place and reset relocation failures remain atomic`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "mixed.sfc").also {
            edit(parser, it, 3, slot = 28)
            edit(parser, it, 0x0F, slot = 28)
        }
        val report = MusicBankTransitions.inspect(parser, project)
        assertTrue(report.canExport)
        assertEquals(setOf(6, 0x3F, 0x42), report.instrumentRestorations.keys)
        val rom = parser.copyRomData()
        val before = rom.copyOf()
        var reservations = 0
        // Let one reset reserve successfully, then force the remaining reservations to fail.
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes,
            onReserve = { if (++reservations == 2) error("Test: allocation exhausted") })
        val failure = assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom, allocator) }
        assertEquals("Test: allocation exhausted", failure.message)
        assertEquals(2, reservations)
        assertContentEquals(before, rom, "Earlier in-place edits, pointers and allocations must all remain unwritten")
    }

    @Test fun `overlapping uploads patch only the final writer and preserve all block metadata`() {
        val rom = TestRomHelper.requireRomParser().copyRomData()
        var parser = RomParser(rom)
        val source = MusicRomTransferChain.read(parser, SpcData.readSongSetPointer(parser, 3))
        val bytes = rom.sliceArray(source.romRange)
        val instrument = bankRam(parser, 3).copyOfRange(0x6C90, 0x6C96)
        val out = java.io.ByteArrayOutputStream().also {
            it.write(bytes, 0, bytes.size - 4)
            it.write(byteArrayOf(6, 0, 0x90.toByte(), 0x6C))
            it.write(instrument)
            it.write(bytes, bytes.size - 4, 4)
        }.toByteArray()
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
            .allocate(out, listOf(0xB8), "duplicate-upload fixture"))
        pointer(rom, SpcData.findSongSetPointerEntryPc(parser, 3), allocation.snesAddress)
        parser = RomParser(rom)
        val project = SmEditProject(romPath = "overlap.sfc").also { edit(parser, it, 3) }
        val expected = MusicBankResolver.resolve(parser, project, 3).ram
        val before = rom.copyOf()
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom, noSpace(parser, rom)))
        val finalDataStart = allocation.pcOffset + out.size - 4 - instrument.size
        val changed = rom.indices.filter { rom[it] != before[it] }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { it in finalDataStart until finalDataStart + instrument.size })
        assertContentEquals(expected, bankRam(RomParser(rom), 3))
        assertEquals(allocation.snesAddress, SpcData.readSongSetPointer(RomParser(rom), 3))
    }

    @Test fun `shared allocator cannot reclaim erased looking bytes inside a live cross bank source`() {
        val rom = TestRomHelper.requireRomParser().copyRomData()
        var parser = RomParser(rom)
        val start = parser.snesToPc(0xB88000)
        // The sample block consumes the rest of B8. Its $FF payload looks free to
        // a trailing-fill scanner, but the terminator is in the following bank.
        val instrument = bankRam(parser, 3).copyOfRange(0x6C90, 0x6C96)
        rom.fill(0xFF.toByte(), start, start + 0x8000)
        byteArrayOf(6, 0, 0x90.toByte(), 0x6C).copyInto(rom, start)
        instrument.copyInto(rom, start + 4)
        byteArrayOf(0xF2.toByte(), 0x7F, 0, 0x80.toByte()).copyInto(rom, start + 10)
        byteArrayOf(0, 0, 0, 0x15).copyInto(rom, start + 0x8000)
        pointer(rom, SpcData.findSongSetPointerEntryPc(parser, 0x48), 0xB88000)
        pointer(rom, SpcData.findSongSetPointerEntryPc(parser, 0x0F), SpcData.readSongSetPointer(parser, 3))
        parser = RomParser(rom)
        val project = SmEditProject(romPath = "live-fill.sfc").also { edit(parser, it, 3) }
        val before = rom.copyOf()
        val external = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes,
            excludedRanges = listOf(0 until start, start + 0x8000 until rom.size))
        val failure = assertFailsWith<IllegalStateException> {
            MusicRomExporter(project, {}).applyMusicEditsToRom(rom, external)
        }
        assertTrue(failure.message.orEmpty().contains("not enough contiguous free ROM space"), failure.message)
        assertContentEquals(before, rom)
        assertNull(external.observing {}.reserve(1, listOf(0xB8), "later exporter must respect music sources"))
    }

    @Test fun `malformed unrelated source is not silently treated as available ROM space`() {
        val rom = TestRomHelper.requireRomParser().copyRomData()
        val parser = RomParser(rom)
        val at = parser.snesToPc(SpcData.readSongSetPointer(parser, 0x48))
        rom[at + 2] = 0xFF.toByte(); rom[at + 3] = 0xFF.toByte()
        val project = SmEditProject(romPath = "malformed.sfc").also { edit(parser, it, 3) }
        val before = rom.copyOf()
        val failure = assertFailsWith<IllegalArgumentException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(failure.message.orEmpty().contains("SPC RAM"))
        assertContentEquals(before, rom)
    }

    @Test fun `reset chains retain the native entry address and only authored banks count as edits`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "reset.sfc").also { edit(parser, it, 3, slot = 28) }
        val before = MusicBankTransitions.snapshot(project)
        val rom = parser.copyRomData()
        val logs = mutableListOf<String>()
        assertEquals(1, MusicRomExporter(project, logs::add).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        assertEquals(SpcData.readSongSetPointer(parser, 3), SpcData.readSongSetPointer(exported, 3))
        assertTrue(logs.any { "in place" in it })
        for (target in listOf(6, 0x3F, 0x42)) {
            val old = SpcData.readSongSetPointer(parser, target)
            val new = SpcData.readSongSetPointer(exported, target)
            assertNotEquals(old, new)
            val chain = MusicRomTransferChain.read(exported, new)
            assertEquals(MusicRomTransferChain.read(parser, old).entryAddress, chain.entryAddress)
            assertEquals(MusicTransferChainBudget.serializedTransferChainSize(chain.blocks), chain.romRange.count())
        }
        assertEquals(before.musicInstrumentBanks, project.musicInstrumentBanks)
        assertEquals(before.musicEdits, project.musicEdits)
    }

    @Test fun `unused native table bank participates in common resource transition checks`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "common.sfc").also { edit(parser, it, 3, slot = 0) }
        assertTrue(MusicBankTransitions.inspect(parser, project).issues.any {
            it.targetSongSet == 0x48 && it.resource == MusicBankTransitions.Resource.COMMON_INSTRUMENT
        })
        val rom = parser.copyRomData()
        val before = rom.copyOf()
        assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertContentEquals(before, rom)
    }
}
