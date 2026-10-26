package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicContinuationExportTest {
    @TempDir lateinit var directory: File
    private fun bank(parser: RomParser, set: Int) = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, set)) }
    private fun edit(parser: RomParser, project: SmEditProject, set: Int, slot: Int, delta: Int = 1) {
        val old = NspcRenderer.readInstrumentTable(bank(parser, set))[slot]
        project.musicInstrumentBanks.getOrPut(MusicInstrumentBanks.key(set)) { MusicInstrumentBankEdit() }
            .instruments[slot] = MusicInstrumentBanks.entry(old.copy(adsr1 = old.adsr1 xor delta))
    }

    @Test fun `actual exported streams restore common and local settings across all destinations without bank copies`() {
        val parser = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc").also { edit(parser, it, 3, 0); edit(parser, it, 0x0F, 0, 2); edit(parser, it, 3, 28) }
        val before = MusicBankTransitions.snapshot(project)
        val source = SpcData.TRANSFER_SONG_SETS.associateWith { SpcTransferStream.read(parser, SpcData.readSongSetPointer(parser, it)) }
        val rom = parser.copyRomData()
        assertEquals(2, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
        assertEquals(parser.copyRomData().size, rom.size)
        val exported = RomParser(rom)
        assertNotNull(SpcTransferStream.installedCodePointer(exported))
        val actual = SpcData.buildInitialSpcRam(exported)
        for (set in listOf(3, 0x0F, 6, 3, 0x42, 0x0F) + SpcData.TRANSFER_SONG_SETS.filter { it > 0 }) {
            SpcData.applyTransferBlocks(actual, SpcData.findSongSetTransferData(exported, set))
            val expected = MusicBankResolver.sourceRam(parser, project, set)
            for (at in listOf(0x6C00, 0x6CA8)) assertContentEquals(expected.copyOfRange(at, at + 6), actual.copyOfRange(at, at + 6), "bank $set, slot $at")
        }
        // Only authored bank-local bytes are patched inside retained banks; all other
        // bank data, BRR and sequence bytes are retained. Common settings live in prefixes.
        for ((set, stream) in source) for (range in stream.romRanges) {
            val expected = parser.copyRomData().sliceArray(range)
            if (set == 3) for ((at, data) in SpcData.buildRomWritesForSpcRamWrites(parser, 3,
                MusicInstrumentBanks.writes(project.musicInstrumentBanks.getValue("03").instruments.filterKeys { it >= 24 }))) {
                data.copyInto(expected, at - range.first)
            }
            assertContentEquals(expected, rom.sliceArray(range))
        }
        assertEquals(before.musicInstrumentBanks, project.musicInstrumentBanks)
        assertEquals(before.musicEdits, project.musicEdits)
        assertTrue(rom.indices.count { rom[it] != parser.getRomData()[it] } < 2048)
    }

    @Test fun `reopening and editing a generated ROM reuses its exact hook and protects every source span`() {
        val base = TestRomHelper.requireRomParser()
        val first = SmEditProject(romPath = "test.sfc").also { edit(base, it, 3, 0) }
        val rom = base.copyRomData()
        MusicRomExporter(first, {}).applyMusicEditsToRom(rom)
        val parser = RomParser(rom.copyOf())
        val hook = assertNotNull(SpcTransferStream.installedCodePointer(parser))
        val next = SmEditProject(romPath = "reopened.sfc").also { edit(parser, it, 3, 0, 2); edit(parser, it, 3, 1) }
        val expected = MusicBankResolver.sourceRam(parser, next, 3)
        MusicRomExporter(next, {}).applyMusicEditsToRom(rom)
        val exported = RomParser(rom)
        assertEquals(hook, SpcTransferStream.installedCodePointer(exported))
        assertContentEquals(expected, bank(exported, 3))
        val allocations = mutableListOf<RomAllocation>()
        val allocator = RomFreeSpaceAllocator(rom, exported::snesToPc, exported::pcToSnes, onReserve = allocations::add)
        assertEquals(0, MusicRomExporter(SmEditProject(romPath = "unchanged.sfc"), {}).applyMusicEditsToRom(rom, allocator))
        val nextSpace = assertNotNull(allocator.reserve(16, listOf(0xB8), "later exporter"))
        for (set in SpcData.TRANSFER_SONG_SETS) for (range in SpcTransferStream.read(exported, SpcData.readSongSetPointer(exported, set)).romRanges) {
            assertFalse(nextSpace.pcOffset <= range.last && nextSpace.pcOffset + nextSpace.size > range.first)
        }
    }

    @Test fun `headered export preserves the copier header and maps the hook and prefixes correctly`() {
        val original = TestRomHelper.requireRomParser().copyRomData()
        val raw = if (original.size % 0x8000 == 512) original.copyOfRange(512, original.size) else original
        val rom = ByteArray(raw.size + 512) { 0x5A }.also { raw.copyInto(it, 512) }
        val parser = RomParser(rom.copyOf())
        val project = SmEditProject(romPath = "headered.smc").also { edit(parser, it, 3, 0) }
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        val exported = RomParser(rom)
        assertTrue(rom.copyOfRange(0, 512).all { it == 0x5A.toByte() })
        assertNotNull(SpcTransferStream.installedCodePointer(exported))
        assertContentEquals(MusicBankResolver.sourceRam(parser, project, 3), bank(exported, 3))
    }

    @Test fun `aliased continued streams isolate covered edits before adding another instrument prefix`() {
        val base = TestRomHelper.requireRomParser()
        val rom = base.copyRomData()
        MusicRomExporter(SmEditProject(romPath = "first.sfc").also { edit(base, it, 3, 0) }, {}).applyMusicEditsToRom(rom)
        val first = RomParser(rom)
        val pointer = SpcData.readSongSetPointer(first, 3)
        val table = SpcData.findSongSetPointerEntryPc(first, 0x48)
        repeat(3) { rom[table + it] = (pointer ushr (it * 8)).toByte() }
        val parser = RomParser(rom.copyOf())
        val retained = SpcTransferStream.read(parser, pointer).romRanges.associateWith { rom.sliceArray(it) }
        val other = bank(parser, 0x48)
        val project = SmEditProject(romPath = "alias.sfc").also { edit(parser, it, 3, 1); edit(parser, it, 3, 24) }
        val expected = MusicBankResolver.sourceRam(parser, project, 3)
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        val exported = RomParser(rom)
        assertContentEquals(expected, bank(exported, 3))
        assertContentEquals(other, bank(exported, 0x48))
        for ((range, data) in retained) assertContentEquals(data, rom.sliceArray(range), "A shared continuation source must not be patched")
        assertEquals(2, SpcTransferStream.read(exported, SpcData.readSongSetPointer(exported, 3)).romRanges.size)
    }

    @Test fun `unknown loader and exhausted mixed reservations leave all ROM bytes unchanged`() {
        val base = TestRomHelper.requireRomParser()
        for (unknown in listOf(false, true)) {
            val rom = base.copyRomData()
            val parser = RomParser(rom)
            if (unknown) rom[parser.snesToPc(0x8080C4)] = 0
            val project = SmEditProject(romPath = "test.sfc").also { edit(parser, it, 3, 0); edit(parser, it, 0x0F, 24) }
            val before = rom.copyOf()
            var count = 0
            val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes,
                onReserve = { if (++count == 3) error("test allocation limit") })
            assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom, allocator) }
            assertContentEquals(before, rom)
            if (!unknown) assertEquals(3, count)
        }
    }

    @Test fun `prefix expansion retains covered bytes from a larger contiguous instrument edit`() {
        val base = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc").also { project ->
            for (slot in 0..24) edit(base, project, 3, slot)
        }
        val rom = base.copyRomData()
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
        val expected = MusicBankResolver.sourceRam(base, project, 3)
        assertContentEquals(expected, bank(RomParser(rom), 3))
        val retained = SpcData.buildRomWritesForSpcRamWrites(base, 3, mapOf(0x6C90 to expected.copyOfRange(0x6C90, 0x6C96)))
        for ((at, data) in retained) assertContentEquals(data, rom.copyOfRange(at, at + data.size))
    }

    @Test fun `partial original entries cannot overwrite their full instrument prefixes`() {
        val rom = TestRomHelper.requireRomParser().copyRomData()
        val original = RomParser(rom)
        val source = SpcTransferStream.read(original, SpcData.readSongSetPointer(original, 3))
        val bytes = rom.sliceArray(source.romRanges.single())
        val partial = byteArrayOf(3, 0, 0, 0x6C) + bank(original, 3).copyOfRange(0x6C00, 0x6C03)
        val altered = bytes.copyOfRange(0, bytes.size - 4) + partial + bytes.takeLast(4).toByteArray()
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, original::snesToPc, original::pcToSnes)
            .allocate(altered, listOf(0xB8), "partial-entry fixture", alignment = 2))
        val table = SpcData.findSongSetPointerEntryPc(original, 3)
        repeat(3) { rom[table + it] = (allocation.snesAddress ushr (it * 8)).toByte() }
        val parser = RomParser(rom.copyOf())
        val project = SmEditProject(romPath = "partial.sfc").also { edit(parser, it, 3, 0) }
        val expected = MusicBankResolver.sourceRam(parser, project, 3)
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        assertContentEquals(expected, bank(RomParser(rom), 3))
        assertContentEquals(expected.copyOfRange(0x6C00, 0x6C03), rom.copyOfRange(
            allocation.pcOffset + altered.size - 7, allocation.pcOffset + altered.size - 4))
    }

    @Test fun `cyclic continuation in an untouched bank blocks reexport without writing bytes`() {
        val base = TestRomHelper.requireRomParser()
        val rom = base.copyRomData()
        MusicRomExporter(SmEditProject(romPath = "test.sfc").also { edit(base, it, 3, 0) }, {}).applyMusicEditsToRom(rom)
        val parser = RomParser(rom)
        val pointer = SpcData.readSongSetPointer(parser, 0x48)
        val stream = SpcTransferStream.read(parser, pointer)
        val marker = stream.romRanges.first().last - 4
        assertEquals(0xFF, rom[marker].toInt() and 255)
        repeat(3) { rom[marker + 2 + it] = (pointer ushr (it * 8)).toByte() }
        val before = rom.copyOf()
        val project = SmEditProject(romPath = "malformed.sfc").also { edit(parser, it, 3, 24) }
        val failure = assertFailsWith<IllegalArgumentException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(failure.message.orEmpty().contains("cyclic"))
        assertContentEquals(before, rom)
    }

    @Test fun `actual exported ROM executes queued bank changes reentry music and soft reset on the CPU and APU`() {
        val base = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc").also { edit(base, it, 3, 0); edit(base, it, 0x0F, 0, 2); edit(base, it, 3, 28) }
        val rom = base.copyRomData()
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        NativeMusicUploadFixture(directory, rom).use { fixture ->
            for (set in listOf(3, 0x0F, 6, 0x42, 3, 0x48, 0x0F, 3)) {
                fixture.queueBank(set)
                val expected = MusicBankResolver.sourceRam(base, project, set)
                for (at in listOf(0x6C00, 0x6CA8)) assertContentEquals(expected.copyOfRange(at, at + 6), fixture.ram().copyOfRange(at, at + 6))
            }
            fixture.play(5)
            assertTrue(fixture.frames(120).any { it != 0.toShort() })
            fixture.queueBank(0x0F) // Leave differently edited common data in retained RAM.
            assertTrue(fixture.softResetToTitle().any { it != 0.toShort() }, "Original soft-reset/title path must resume music")
            val title = MusicBankResolver.sourceRam(base, project, 3)
            assertContentEquals(title.copyOfRange(0x6C00, 0x6C06), fixture.ram().copyOfRange(0x6C00, 0x6C06))
        }
    }

    @Test fun `exported instrument restoration leaves each native effect library audible without background music`() {
        val base = TestRomHelper.requireRomParser()
        val project = SmEditProject(romPath = "test.sfc").also { edit(base, it, 3, 0) }
        val rom = base.copyRomData()
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        for (library in 1..3) NativeMusicUploadFixture(File(directory, "effect-$library"), rom).use { fixture ->
            for (set in listOf(3, 0x0F)) fixture.queueBank(set)
            assertContentEquals(bank(base, 0x0F).copyOfRange(0x6C00, 0x6C06), fixture.ram().copyOfRange(0x6C00, 0x6C06))
            fixture.effect(library, 0)
            fixture.frames(8)
            assertTrue(fixture.frames(30).all { it == 0.toShort() }, "No background music may mask the effect check")
            fixture.effect(library, if (library == 3) 5 else 1) // Library 3 / 1 is deliberately Silence.
            assertTrue(fixture.frames(60).any { it != 0.toShort() }, "Effect library $library must remain audible")
        }
    }
}
