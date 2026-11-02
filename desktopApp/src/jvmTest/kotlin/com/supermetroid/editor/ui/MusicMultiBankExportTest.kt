package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

internal fun musicWithRetainedSampleSnapshot(parser: RomParser, romPath: String = "test.sfc"): SmEditProject {
    val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
    val ram = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, 3)) }
    val song = NspcSequence.parse(ram, 5)
    // A native sample snapshot keeps common recordings as well as this bank's own samples.
    val at = MusicSequenceBudget.SONG_TABLE_BASE + 10
    val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(listOf(
        SpcData.TransferBlock(at, ram.copyOfRange(at, at + 2)),
        SpcData.TransferBlock(0x6C00, ram.copyOfRange(0x6C00, 0x10000))), 5, "full-samples.nspc", "Raw N-SPC"), song)
    return SmEditProject(romPath).apply { musicEdits["03:05"] = MusicEditConversion.toProjectEdit(track, song, payload) }
}

class MusicMultiBankExportTest {
    @TempDir lateinit var directory: File
    private fun bank(parser: RomParser, set: Int) = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, set)) }
    private fun largeProject(parser: RomParser) = musicWithRetainedSampleSnapshot(parser)
    private fun sourceRanges(parser: RomParser) = (SpcData.TRANSFER_SONG_SETS.map { SpcData.readSongSetPointer(parser, it) } + 0xCF8000)
        .distinct().flatMap { SpcTransferStream.read(parser, it).romRanges }
    private fun stageLayout(parser: RomParser, rom: ByteArray, layout: MusicTransferChainLayout.Layout): Pair<Int, List<RomAllocation>> {
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, guardBytes = 2)
        allocator.protectRanges(sourceRanges(parser))
        val code = assertNotNull(allocator.reserve(MusicTransferLoader.code.size, (0xB8..0xDF).toList(), "test loader", 16))
        val segments = layout.segments.map { assertNotNull(allocator.reserve(it.size, listOf(0xB8, 0xCE, 0x85, 0x83, 0x89), "test segment", 16)) }
        MusicTransferLoader.patchWrites(parser, code.snesAddress).forEach { (at, bytes) -> bytes.copyInto(rom, at) }
        segments.forEachIndexed { index, allocation -> allocator.write(allocation, layout.encode(index, segments.getOrNull(index + 1)?.snesAddress)) }
        return segments.first().snesAddress to segments
    }
    @Test fun `complete 64KB RAM upload splits blocks across multiple ROM banks without losing bytes or entry address`() {
        val parser = TestRomHelper.requireRomParser(); val rom = parser.copyRomData()
        val data = ByteArray(65536) { ((it * 31) xor (it ushr 8)).toByte() }
        val layout = MusicTransferChainLayout.plan(listOf(SpcData.TransferBlock(0, data)), 0x2345)
        assertTrue(layout.continued)
        val (pointer, allocations) = stageLayout(parser, rom, layout)
        assertTrue(allocations.map { it.bank }.distinct().size > 1)
        assertTrue(allocations.all { (it.snesAddress and 65535) + it.size <= 65536 })
        val result = SpcTransferStream.read(RomParser(rom), pointer)
        val ram = ByteArray(65536).also { SpcData.applyTransferBlocks(it, result.blocks) }
        assertTrue(data.contentEquals(ram), "Every byte must retain its original SPC address")
        assertEquals(0x2345, result.entryAddress)
        assertTrue(result.blocks.none { it.data.size == 0 || it.data.size == 65535 })
        assertEquals(layout.size, result.romRanges.sumOf { it.count() })
    }
    @Test fun `small sparse and exact size uploads preserve terminators while malformed layouts fail`() {
        for (size in listOf(0, 1, 0x4000 - 8, 0x4000 - 7, 32760, 32761, 65535, 65536)) {
            val data = ByteArray(size) { it.toByte() }
            val layout = MusicTransferChainLayout.plan(listOf(SpcData.TransferBlock(0, data)), 0x1500)
            val parser = TestRomHelper.requireRomParser(); val rom = parser.copyRomData()
            val (pointer, _) = stageLayout(parser, rom, layout)
            val read = SpcTransferStream.read(RomParser(rom), pointer)
            val ram = ByteArray(65536).also { SpcData.applyTransferBlocks(it, read.blocks) }
            assertTrue(data.contentEquals(ram.copyOfRange(0, size)), "Boundary size $size")
            assertEquals(0x1500, read.entryAddress)
            if (layout.continued) assertFails { layout.encode(0, null) }
            assertFails { layout.encode(layout.segments.lastIndex, 0xB88000) }
        }
        assertFails { MusicTransferChainLayout.plan(listOf(SpcData.TransferBlock(65535, ByteArray(2))), 0x1500) }
        assertFails { MusicTransferChainLayout.plan(emptyList(), 65536) }
        assertFails { MusicTransferChainLayout.plan(List(4096) { SpcData.TransferBlock(0, byteArrayOf(1)) }, 0x1500) }
    }
    @Test fun `large native bank retains sibling and common assets exports exactly and reuses its installed loader`() {
        val parser = TestRomHelper.requireRomParser(); val project = largeProject(parser)
        val before = MusicBankTransitions.snapshot(project)
        val expected = MusicBankResolver.resolve(parser, project, 3).ram
        val sibling = NspcSequence.parse(expected, 6)
        val rom = parser.copyRomData(); val allocations = mutableListOf<RomAllocation>()
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, onReserve = allocations::add)
        assertEquals(1, MusicRomExporter(project, {}).applyMusicEditsToRom(rom, allocator))
        val exported = RomParser(rom.copyOf())
        val hook = assertNotNull(SpcTransferStream.installedCodePointer(exported))
        val stream = SpcTransferStream.read(exported, SpcData.readSongSetPointer(exported, 3))
        assertTrue(stream.romRanges.sumOf { it.count() } > 32768)
        assertTrue(stream.romRanges.size > 1)
        assertTrue(expected.contentEquals(bank(exported, 3)))
        assertEquals(sibling, NspcSequence.parse(bank(exported, 3), 6))
        for (set in SpcData.TRANSFER_SONG_SETS.filter { it != 3 }) assertEquals(SpcData.readSongSetPointer(parser, set), SpcData.readSongSetPointer(exported, set))
        assertEquals(before.musicEdits, project.musicEdits)
        val later = assertNotNull(allocator.reserve(16, listOf(0xB8, 0x85), "later resource"))
        assertTrue(stream.romRanges.none { later.pcOffset <= it.last && later.pcOffset + later.size > it.first })
        val next = SmEditProject("reopened.sfc")
        val sound = NspcRenderer.readInstrumentTable(expected)[24]
        next.musicInstrumentBanks["03"] = MusicInstrumentBankEdit(mutableMapOf(24 to MusicInstrumentBanks.entry(sound.copy(gain = sound.gain xor 1))))
        MusicRomExporter(next, {}).applyMusicEditsToRom(rom)
        assertEquals(hook, SpcTransferStream.installedCodePointer(RomParser(rom)))
        assertTrue(MusicBankResolver.resolve(exported, next, 3).ram.contentEquals(bank(RomParser(rom), 3)))
    }
    @Test fun `large upload plays through actual game queue bank reentry effects and original title reset`() {
        val parser = TestRomHelper.requireRomParser(); val project = largeProject(parser)
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        val exported = RomParser(rom)
        NativeMusicUploadFixture(directory, rom).use { runtime ->
            val expected = SpcData.buildInitialSpcRam(exported)
            runtime.upload(SpcData.readSongSetPointer(exported, 3), narrowCaller = true)
            SpcData.applyTransferBlocks(expected, SpcData.findSongSetTransferData(exported, 3))
            for (set in listOf(3, 0x0F, 6, 3)) {
                runtime.queueBank(set)
                SpcData.applyTransferBlocks(expected, SpcData.findSongSetTransferData(exported, set))
                assertTrue(expected.copyOfRange(0x6C00, 0x10000).contentEquals(runtime.ram().copyOfRange(0x6C00, 0x10000)), "Actual retained sample bytes in bank $set")
            }
            for ((library, id) in listOf(1 to 0x37, 2 to 1, 3 to 5)) { runtime.effect(library, id); assertTrue(runtime.frames(180).any { it != 0.toShort() }) }
            runtime.play(5); assertTrue(runtime.frames(180).any { it != 0.toShort() })
            assertTrue(runtime.softResetToTitle().any { it != 0.toShort() })
        }
    }
    @Test fun `allocation exhaustion after earlier segment reservations leaves every ROM byte untouched`() {
        val parser = TestRomHelper.requireRomParser(); val project = largeProject(parser)
        val rom = parser.copyRomData(); val allocations = mutableListOf<RomAllocation>()
        val end = parser.snesToPc(0xB8FFFF) + 1; val start = end - 0x3000
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, onReserve = allocations::add,
            excludedRanges = listOf(0 until start, end until rom.size))
        assertFails { MusicRomExporter(project, {}).applyMusicEditsToRom(rom, allocator) }
        assertTrue(allocations.size >= 2, "Exercise failure after successful reservations")
        assertTrue(rom.contentEquals(parser.getRomData()))
    }
    @Test fun `headered large uploads preserve copier bytes and protected original sources`() {
        val original = TestRomHelper.requireRomBytes(); val header = ByteArray(512) { 0x5A }
        val parser = RomParser(header + original); val project = largeProject(parser)
        val rom = parser.copyRomData(); val sources = sourceRanges(parser)
        MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        assertContentEquals(header, rom.copyOfRange(0, 512))
        assertTrue(MusicBankResolver.resolve(parser, project, 3).ram.contentEquals(bank(RomParser(rom), 3)))
        for (range in sources) assertTrue(parser.getRomData().sliceArray(range).contentEquals(rom.sliceArray(range)), "Retained source $range")
    }
}
