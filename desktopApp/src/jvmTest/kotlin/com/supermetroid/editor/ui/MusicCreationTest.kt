package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicCreationTest {
    @TempDir lateinit var directory: File
    private fun context(): Pair<RomParser, EditorState> {
        val file = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        return RomParser(file.readBytes()) to EditorState().apply { testMode = true; initForRom(file.path) }
    }
    @Test fun `blank new track persists independently and creation undo redo and piano reset preserve its baseline`() {
        val (parser, editor) = context()
        val plan = MusicCreation.plan(parser, editor.project, "My first song", 3, "My palette", null)
        assertEquals(0x4B, plan.track.songSet); assertEquals(5, plan.track.playIndex)
        assertTrue(editor.project.musicTracks.isEmpty(), "Checking creation is detached")
        editor.applyMusicCreation(plan)
        val state = SoundEditorState().apply { selectTrack(plan.track); openPianoRoll(parser, editor) }
        assertEquals(0, state.editingSong!!.channels.sumOf { it.notes.size })
        val changed = PianoRollPreviewLogic.deepCopySong(state.editingSong!!).apply { channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 24) }
        state.recordPianoRollEdit("Add note"); state.notifySongChanged(changed, editor)
        assertTrue(editor.project.musicEdits.isNotEmpty())
        assertFalse(editor.undo(), "Creation cannot overwrite a later piano edit")
        assertTrue(state.undoPianoRollEdit(editor)); assertTrue(editor.project.musicEdits.isEmpty())
        assertTrue(editor.undo()); assertTrue(editor.project.musicTracks.isEmpty()); assertTrue(editor.project.musicBanks.isEmpty())
        assertTrue(editor.redo()); assertEquals("My first song", MusicCatalog.tracks(editor.project).last().name)
        val file = File(directory, "music.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, file.path, null, false, {}))
        val reopened = ProjectFileService.loadProject(file)
        assertEquals(editor.project.musicTracks, reopened.musicTracks); assertEquals(editor.project.musicBanks, reopened.musicBanks)
        assertContentEquals(MusicBankResolver.resolve(parser, editor.project, 0x4B).ram, MusicBankResolver.resolve(parser, reopened, 0x4B).ram)
        state.resetPianoRoll(parser, editor)
        assertTrue(state.editingSong!!.channels.all { it.notes.isEmpty() }); assertEquals(1, editor.project.musicTracks.size)
    }
    @Test fun `duplicate tracks in a cloned palette receive independent entries and production ROM chains`() {
        val (parser, editor) = context()
        val source = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val first = MusicCreation.plan(parser, editor.project, "New theme", 3, "New world", source)
        editor.applyMusicCreation(first)
        val second = MusicCreation.plan(parser, editor.project, "Theme variation", 0x4B, null, first.track)
        editor.applyMusicCreation(second)
        assertEquals(6, second.track.playIndex)
        val expected = MusicBankResolver.resolve(parser, editor.project, 0x4B).ram
        assertEquals(MusicCatalog.baseline(editor.project, 0x4B, 5)!!.channels.map { it.notes }, NspcSequence.parse(expected, 5).channels.map { it.notes })
        assertEquals(NspcSequence.parse(expected, 5), NspcSequence.parse(expected, 6))
        val rom = parser.copyRomData()
        assertTrue(MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom) > 0)
        val exported = RomParser(rom)
        assertTrue(SpcMusicBankTable.read(exported).expanded); assertTrue(0x4B in SpcMusicBankTable.songSets(exported))
        assertNotEquals(SpcData.readSongSetPointer(exported, 3), SpcData.readSongSetPointer(exported, 0x4B))
        val actual = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 0x4B)) }
        assertContentEquals(expected, actual)
        for (set in SpcData.TRANSFER_SONG_SETS) assertEquals(SpcData.readSongSetPointer(parser, set), SpcData.readSongSetPointer(exported, set))
        NativeMusicUploadFixture(File(directory, "console"), rom).use { runtime ->
            for ((set, play) in listOf(0x4B to 5, 3 to 5, 0x4B to 6)) {
                runtime.queueBank(set); runtime.play(play)
                assertTrue(runtime.frames(180).any { it != 0.toShort() }, "$set/$play must play via the actual expanded CPU table")
            }
        }
    }
    @Test fun `bank palette copies owned samples and settings without linking later edits to its source`() = runBlocking {
        val (parser, editor) = context()
        val target = SampleBankPreview.Target(3, NspcSequence.Note(0, 48, 0xA4, instrument = 24), "Title", "Sound", MusicBankTransitions.snapshot(editor.project))
        val sample = MusicSampleBanks.plan(parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE)), "Sine")
        editor.setMusicInstrumentBank(3, sample.bank)
        val created = MusicCreation.plan(parser, editor.project, "Sine song", 3, "Sine palette", null)
        editor.applyMusicCreation(created)
        val clone = editor.project.musicInstrumentBanks.getValue("4B")
        assertEquals(sample.bank, clone); assertNotSame(sample.bank, clone)
        val original = MusicBankResolver.sourceRam(parser, editor.project, 3)
        val ram = MusicBankResolver.sourceRam(parser, editor.project, 0x4B)
        assertContentEquals(original.copyOfRange(0x6C90, 0x6C96), ram.copyOfRange(0x6C90, 0x6C96))
        clone.instruments[24] = clone.instruments.getValue(24).copy(gain = 32)
        assertNotEquals(editor.project.musicInstrumentBanks.getValue("03").instruments[24], clone.instruments[24])
        val rom = parser.copyRomData(); MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom)
        Unit
    }
    @Test fun `unsafe table cells and exhausted ROM allocations leave the input untouched`() {
        val (parser, editor) = context()
        val plan = MusicCreation.plan(parser, editor.project, "Safe", 3, "Palette", null)
        val rom = parser.copyRomData()
        val blocked = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes, excludedRanges = listOf(rom.indices))
        assertFails { MusicRomExporter(plan.after, {}).applyMusicEditsToRom(rom, blocked) }
        assertContentEquals(parser.getRomData(), rom)
        val bad = MusicBankTransitions.snapshot(editor.project)
        bad.musicTracks[MusicTrackEdit.key(0x0F, 6)] = MusicTrackDefinition(0x0F, 6, "Collision", MusicNativeBaselineEdit(36))
        assertFails { MusicRomExporter(bad, {}).applyMusicEditsToRom(rom) }
        assertContentEquals(parser.getRomData(), rom)
    }
    @Test fun `an existing bank claims only an unreferenced table cell and retains all native arrangements`() {
        val (parser, editor) = context()
        val before = MusicBankResolver.sourceRam(parser, editor.project, 6)
        val plan = MusicCreation.plan(parser, editor.project, "Rain interlude", 6, null, null)
        assertFalse(plan.bankCreated); assertTrue(plan.after.musicBanks.isEmpty())
        val after = MusicBankResolver.resolve(parser, plan.after, 6).ram
        for (track in SpcData.KNOWN_TRACKS.filter { it.songSet == 6 }) assertEquals(NspcSequence.parse(before, track.playIndex), NspcSequence.parse(after, track.playIndex))
        assertTrue(NspcSequence.parse(after, plan.track.playIndex).channels.all { it.notes.isEmpty() })
        val rom = parser.copyRomData(); MusicRomExporter(plan.after, {}).applyMusicEditsToRom(rom)
        assertFalse(SpcMusicBankTable.read(RomParser(rom)).expanded)
    }
    @Test fun `highest supported bank and track IDs play through the native queue and reopening retains table ownership`() {
        val (parser, editor) = context()
        val source = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
        val initial = MusicCreation.plan(parser, editor.project, "High IDs", 3, "High bank", source)
        val project = MusicBankTransitions.snapshot(initial.after)
        val bank = project.musicBanks.remove("4B")!!
        val track = project.musicTracks.remove("4B:05")!!
        project.musicBanks["FC"] = bank.copy(songSet = 0xFC)
        project.musicTracks["FC:7F"] = track.copy(songSet = 0xFC, playIndex = 127)
        val rom = parser.copyRomData(); MusicRomExporter(project, {}).applyMusicEditsToRom(rom)
        NativeMusicUploadFixture(File(directory, "high-ids"), rom).use { runtime ->
            runtime.queueBank(0xFC); runtime.play(127)
            assertTrue(runtime.frames(180).any { it != 0.toShort() })
        }
        val reopened = RomParser(rom)
        val allocations = mutableListOf<RomAllocation>()
        val allocator = RomFreeSpaceAllocator(rom, reopened::snesToPc, reopened::pcToSnes, onReserve = allocations::add)
        assertEquals(0, MusicRomExporter(SmEditProject(romPath = "export.sfc"), {}).applyMusicEditsToRom(rom, allocator))
        val reserved = assertNotNull(allocator.reserve(16, listOf(0xB8, 0xB9, 0xBA), "later writer"))
        val table = SpcMusicBankTable.read(reopened)
        val ranges = listOf(table.pc - SpcMusicBankTable.HEADER_BYTES until table.pc + SpcMusicBankTable.TABLE_BYTES) +
            SpcTransferStream.read(reopened, SpcData.readSongSetPointer(reopened, 0xFC)).romRanges
        assertTrue(ranges.none { reserved.pcOffset <= it.last && reserved.pcOffset + reserved.size > it.first })
    }
    @Test fun `room assignment saves and exports the new track bytes and blocks creation undo while referenced`() {
        val (parser, editor) = context()
        val plan = MusicCreation.plan(parser, editor.project, "Landing theme", 3, "Landing palette", SpcData.KNOWN_TRACKS.first())
        editor.applyMusicCreation(plan)
        val room = editor.project.getOrCreateRoom(0x91F8)
        room.stateDataChange = StateDataChange(musicData = plan.track.songSet, musicTrack = plan.track.playIndex)
        assertFalse(editor.undo()); assertEquals(1, editor.project.musicTracks.size)
        val file = File(directory, "assigned.smedit")
        assertTrue(ProjectFileService.saveProject(editor.project, file.path, null, false, {}))
        val saved = ProjectFileService.loadProject(file)
        val rom = parser.copyRomData()
        val allocator = RomFreeSpaceAllocator(rom, parser::snesToPc, parser::pcToSnes)
        MusicRomExporter(saved, {}).applyMusicEditsToRom(rom, allocator)
        ProjectRoomExporter(saved, parser, rom, freeSpaceAllocator = allocator).exportRooms()
        val actual = assertNotNull(RomParser(rom).readRoomHeader(0x91F8))
        assertEquals(plan.track.songSet, actual.musicData); assertEquals(plan.track.playIndex, actual.musicTrack)
        room.stateDataChange = null
        assertTrue(editor.undo()); assertTrue(editor.redo())
    }
    @Test fun `cloning an imported palette freezes sample bytes independently of later source-track reset`() {
        val (parser, editor) = context()
        val source = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
        val song = NspcSequence.Song().apply { channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 24) }
        val sample = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        val writes = NspcSequence.encode(song, 5, conductorAddrOverride = 0x6500, sequenceEndAddr = 0x6900)
        val blocks = writes.map { (at, bytes) -> SpcData.TransferBlock(at, bytes) } + listOf(
            SpcData.TransferBlock(0x6000, sample), SpcData.TransferBlock(0x6D60, byteArrayOf(0, 0x60, 0, 0x60)),
            SpcData.TransferBlock(0x6C90, PianoRollPreviewLogic.instrumentBytes(NspcRenderer.InstrumentEntry(24, 0, 0, 0x60, 1, 24, 0x6C90))))
        val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, 5, "palette.nspc", "Raw N-SPC"), song)
        editor.setMusicEdit("03:06", MusicEditConversion.toProjectEdit(source, song, payload))
        val plan = MusicCreation.plan(parser, editor.project, "Imported copy", 3, "Imported palette", source)
        editor.applyMusicCreation(plan)
        editor.project.musicEdits.remove("03:06")
        val ram = MusicBankResolver.resolve(parser, editor.project, 0x4B).ram
        assertContentEquals(sample, ram.copyOfRange(0x6000, 0x6009))
        assertTrue(editor.project.musicBanks.getValue("4B").sourceBlocks.isNotEmpty())
        val pcm = NativeSpcEmulator().use { it.loadFromRam(ram, emptyList(), 5, playbackWarmupSeconds = 0); it.renderMono(2) }
        assertTrue(pcm.any { it != 0.toShort() })
        val rom = parser.copyRomData(); MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom)
    }
}
