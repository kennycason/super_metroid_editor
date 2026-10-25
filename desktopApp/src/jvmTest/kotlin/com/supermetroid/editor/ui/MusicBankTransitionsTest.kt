package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import kotlin.test.*

class MusicBankTransitionsTest {
    private val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
    private fun parser() = TestRomHelper.requireRomParser()
    private fun project() = SmEditProject(romPath = "test.sfc")
    private fun original(parser: RomParser, songSet: Int) = SpcData.buildInitialSpcRam(parser).also {
        SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
    }
    private fun gain(parser: RomParser, project: SmEditProject, songSet: Int, slot: Int, value: Int) {
        val entry = NspcRenderer.readInstrumentTable(original(parser, songSet))[slot].copy(gain = value)
        project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)] = MusicInstrumentBankEdit(mutableMapOf(
            slot to MusicInstrumentBanks.entry(entry)))
    }
    private fun native(project: SmEditProject, extra: List<SpcData.TransferBlock> = emptyList()) {
        val song = NspcSequence.Song(tempo = 36).also {
            it.channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 24)
        }
        val instrument = NspcRenderer.InstrumentEntry(24, 0, 0, 0x60, 0x0001, 24, 0x6C90)
        val blocks = NspcSequence.encode(song, 5, conductorAddrOverride = 0x6500)
            .map { (at, data) -> SpcData.TransferBlock(at, data) } + listOf(
            SpcData.TransferBlock(0x6C90, PianoRollPreviewLogic.instrumentBytes(instrument)),
            SpcData.TransferBlock(0x6D60, byteArrayOf(0, 0x60, 0, 0x60)),
            SpcData.TransferBlock(0x6000, byteArrayOf(3, 1, 2, 3, 4, 5, 6, 7, 8))) + extra
        val payload = MusicEditConversion.toProjectNativePayload(
            MusicTrackInterchange.NativePayload(blocks, 5, "source.nspc", "Raw N-SPC"), song)
        project.musicEdits[MusicEditConversion.key(3, 6)] = MusicEditConversion.toProjectEdit(track, song, payload)
    }

    @Test fun `untouched projects need no transition check`() {
        val report = MusicBankTransitions.inspect(parser(), project())
        assertTrue(report.canExport)
        assertEquals(0, report.checkedTransitions)
    }

    @Test fun `common edits that sound correct in fresh RAM are rejected before any ROM writes`() {
        val parser = parser()
        val project = project()
        val original = parser.copyRomData()
        val stock = NspcRenderer.readInstrumentTable(original(parser, 3))[0]
        gain(parser, project, 3, 0, stock.gain xor 1)
        assertEquals(stock.gain xor 1, MusicBankResolver.resolve(parser, project, 3).ram[0x6C03].toInt() and 255)
        val report = MusicBankTransitions.inspect(parser, project)
        assertEquals((SpcData.SONG_SETS.size - 1) * 2, report.checkedTransitions)
        assertTrue(report.issues.any { it.sourceSongSet == 3 && it.targetSongSet == 0x0F &&
            it.resource == MusicBankTransitions.Resource.COMMON_INSTRUMENT && it.start == 0x6C03 })
        val rom = original.copyOf()
        val error = assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(error.message!!.contains("not restored"))
        assertContentEquals(original, rom)
        // Isolated previews and raw banks are still useful for sound design.
        assertTrue(MusicBankResolver.resolve(parser, project, 3).blocks.isNotEmpty())
    }

    @Test fun `bank loaded instrument edits survive real exported transitions without touching common sounds`() {
        val parser = parser()
        val project = project()
        val stock = NspcRenderer.readInstrumentTable(original(parser, 3))[28]
        gain(parser, project, 3, 28, stock.gain xor 1)
        val report = MusicBankTransitions.inspect(parser, project)
        assertTrue(report.canExport, report.issues.take(12).joinToString())
        assertEquals(setOf(6, 0x3F, 0x42), report.instrumentRestorations.keys,
            "Rain and voice banks do not normally reload slot 28")
        val rom = parser.copyRomData()
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val ram = SpcData.buildInitialSpcRam(exported)
        SpcData.applyTransferBlocks(ram, SpcData.findSongSetTransferData(exported, 3))
        assertEquals(stock.gain xor 1, ram[0x6CAB].toInt() and 255)
        for (target in SpcData.SONG_SETS.filter { it != 3 }) {
            val transitioned = ram.copyOf().also {
                SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, target))
            }
            val expected = original(parser, target)
            assertContentEquals(expected.copyOfRange(0x6C00, 0x6CB0), transitioned.copyOfRange(0x6C00, 0x6CB0))
        }
    }

    @Test fun `bank scoped native samples pass while shared directory sample and engine overwrites are reported`() {
        val parser = parser()
        val safe = project().also { native(it) }
        val safeReport = MusicBankTransitions.inspect(parser, safe)
        assertTrue(safeReport.canExport, safeReport.issues.take(12).joinToString())
        val base = SpcData.buildInitialSpcRam(parser)
        val cases = listOf(
            0x6D00 to MusicBankTransitions.Resource.DIRECTORY,
            0x6E00 to MusicBankTransitions.Resource.SAMPLE,
            0x1500 to MusicBankTransitions.Resource.ENGINE)
        for ((at, resource) in cases) {
            val project = project().also { native(it, listOf(SpcData.TransferBlock(at, byteArrayOf((base[at].toInt() xor 1).toByte())))) }
            val report = MusicBankTransitions.inspect(parser, project)
            assertFalse(report.canExport, "${resource.label} must not leak into later banks")
            assertTrue(report.issues.any { it.resource == resource && it.start == at }, "$resource: ${report.issues.take(4)}")
        }
    }

    @Test fun `new sample dependencies must be restored when entering an edited bank from an unedited one`() {
        val parser = parser()
        val rom = parser.copyRomData()
        // A fixture bank that inherits local samples from the initial upload.
        // Cold previews can play them, but another bank overwrites those bytes.
        val out = java.io.ByteArrayOutputStream()
        for (block in SpcData.findSongSetTransferData(parser, 3).filter { it.destAddr < 0xB000 }) {
            for (word in listOf(block.data.size, block.destAddr)) {
                out.write(word and 255); out.write(word ushr 8)
            }
            out.write(block.data)
        }
        out.write(0); out.write(0)
        val allocation = assertNotNull(RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
            .allocate(out.toByteArray(), listOf(0xB8), "inherited-sample test bank"))
        val pointer = SpcData.findSongSetPointerEntryPc(parser, 3)
        repeat(3) { rom[pointer + it] = (allocation.snesAddress ushr (it * 8)).toByte() }
        val inheritedParser = RomParser(rom)
        val project = project()
        val instrument = NspcRenderer.readInstrumentTable(original(inheritedParser, 3))[24]
        gain(inheritedParser, project, 3, 24, instrument.gain xor 1)
        val report = MusicBankTransitions.inspect(inheritedParser, project)
        assertTrue(report.issues.any { it.sourceSongSet == 0x0F && it.targetSongSet == 3 &&
            it.editedSongSet == 3 && it.resource == MusicBankTransitions.Resource.SAMPLE }, report.issues.take(12).joinToString())
        assertFalse(report.canExport)
    }

    @Test fun `reset uploads preserve different authored settings when moving back and forth between banks`() {
        val parser = parser()
        val project = project()
        gain(parser, project, 3, 28, 31)
        gain(parser, project, 0x0F, 28, 63)
        val before = MusicBankTransitions.snapshot(project)
        // This test exercises transitions, not vanilla ROM free-space limits.
        val originalBytes = parser.copyRomData()
        val rom = originalBytes.copyOf(0x400000).also { it.fill(0xFF.toByte(), originalBytes.size) }
        assertEquals(2, MusicRomExporter(project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val ram = SpcData.buildInitialSpcRam(exported)
        for (target in listOf(3, 0x0F, 6, 3, 0x42, 0x0F, 0x3F)) {
            SpcData.applyTransferBlocks(ram, SpcData.findSongSetTransferData(exported, target))
            val expected = MusicBankResolver.resolve(parser, project, target).ram
            assertContentEquals(expected.copyOfRange(0x6CA8, 0x6CAE), ram.copyOfRange(0x6CA8, 0x6CAE),
                "Bank ${MusicInstrumentBanks.key(target)} must restore its own settings")
        }
        assertEquals(before.musicInstrumentBanks, project.musicInstrumentBanks, "Export resets are derived uploads, not project edits")
        assertTrue(project.musicEdits.isEmpty())
    }

    @Test fun `insufficient room for reset uploads leaves all ROM bytes and pointers untouched`() {
        val parser = parser()
        val project = project()
        gain(parser, project, 3, 28, 31)
        gain(parser, project, 0x0F, 28, 63)
        val rom = parser.copyRomData()
        val before = rom.copyOf()
        val error = assertFailsWith<IllegalStateException> { MusicRomExporter(project, {}).applyMusicEditsToRom(rom) }
        assertTrue(error.message!!.contains("not enough contiguous free ROM space"))
        assertContentEquals(before, rom)
    }

    @Test fun `checking only edited banks cannot hide leaks into untouched banks or across intermediate loads`() {
        val parser = parser()
        val project = project()
        val stock = NspcRenderer.readInstrumentTable(original(parser, 3))[0]
        val value = stock.gain xor 1
        gain(parser, project, 3, 0, value)
        gain(parser, project, 0x0F, 0, value)
        val report = MusicBankTransitions.inspect(parser, project)
        assertFalse(report.issues.any { it.sourceSongSet == 3 && it.targetSongSet == 0x0F })
        assertTrue(report.issues.any { it.sourceSongSet == 0x0F && it.targetSongSet == 0x18 })
        val ram = MusicBankResolver.resolve(parser, project, 3).ram.copyOf()
        for (target in listOf(0x0F, 0x18)) SpcData.applyTransferBlocks(ram, SpcData.findSongSetTransferData(parser, target))
        assertEquals(value, ram[0x6C03].toInt() and 255)
        assertEquals(stock.gain, original(parser, 0x18)[0x6C03].toInt() and 255)
    }

    @Test fun `background snapshots are detached and analysis never mutates the project or ROM`() {
        val parser = parser()
        val project = project().also { native(it); gain(parser, it, 3, 28, 63) }
        val copy = MusicBankTransitions.snapshot(project)
        val before = parser.copyRomData()
        MusicBankTransitions.inspect(parser, copy)
        assertContentEquals(before, parser.copyRomData())
        assertEquals(project.musicEdits, copy.musicEdits)
        copy.musicEdits.values.single().channels[0].notes.clear()
        copy.musicEdits.values.single().nativePayload!!.baseline.channels[0].notes.clear()
        copy.musicInstrumentBanks.getValue("03").instruments.clear()
        assertEquals(1, project.musicEdits.values.single().channels[0].notes.size)
        assertEquals(1, project.musicEdits.values.single().nativePayload!!.baseline.channels[0].notes.size)
        assertEquals(1, project.musicInstrumentBanks.getValue("03").instruments.size)
    }
}
