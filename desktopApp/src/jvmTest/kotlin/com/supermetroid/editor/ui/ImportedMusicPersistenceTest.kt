package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.*

class ImportedMusicPersistenceTest {
    @TempDir lateinit var directory: File
    private val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
    private val sample = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())

    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState,
        val song: NspcSequence.Song, val instruments: List<NspcRenderer.InstrumentEntry>, val payload: MusicNativePayloadEdit)

    private fun fixture(): Fixture {
        val romFile = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(romFile.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(romFile.absolutePath) }
        val sound = SoundEditorState().apply { selectTrack(track); openPianoRoll(parser, editor) }
        val song = NspcSequence.Song(tempo = 36)
        song.channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 0)
        val instrument = NspcRenderer.InstrumentEntry(0, 0, 0, 0x60, 0x0001, 0, 0x6C00)
        val writes = NspcSequence.encode(song, 5, conductorAddrOverride = 0x7000, sequenceEndAddr = 0x7400)
        val blocks = writes.map { (at, data) -> SpcData.TransferBlock(at, data) } + listOf(
            SpcData.TransferBlock(0x6C00, PianoRollPreviewLogic.instrumentBytes(instrument)),
            SpcData.TransferBlock(0x6D00, byteArrayOf(0, 0x60, 0, 0x60)),
            SpcData.TransferBlock(0x6000, sample)
        )
        val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, 5, "custom.nspc", "Raw N-SPC"), song)
        val importedRam = ByteArray(65536).also { SpcData.applyTransferBlocks(it, blocks) }
        val importedInstruments = NspcRenderer.readInstrumentTable(importedRam)
        sound.applyImportedPianoRollSong(song, importedInstruments, payload, editor, "test native")
        return Fixture(parser, editor, sound, song, importedInstruments, payload)
    }

    private fun saved(f: Fixture) = assertNotNull(f.editor.getMusicEdit(track.songSet, track.playIndex))

    private fun assertSample(ram: ByteArray) {
        assertContentEquals(sample, ram.copyOfRange(0x6000, 0x6000 + sample.size))
        assertContentEquals(byteArrayOf(0, 0x60, 0, 0x60), ram.copyOfRange(0x6D00, 0x6D04))
    }

    @Test fun `native import keeps pristine source bytes until the editable sequence changes`() {
        val f = fixture()
        val initial = saved(f)
        assertNotNull(initial.nativePayload?.baseline)
        val pristine = ImportedMusicResolver.resolve(f.parser, initial)
        assertNull(pristine.allocation, "Generated E0 and tempo commands must not force a pristine import to re-encode")
        assertSample(pristine.ram)
        val source = initial.nativePayload!!.blocks.toList()
        f.sound.recordPianoRollEdit("Move note")
        val song = f.sound.editingSong!!
        song.channels[0].notes.single().tick = 12
        song.channels[0].notes.single().noteValue = 0xA8
        f.sound.notifySongChanged(song, f.editor)
        assertEquals(source, saved(f).nativePayload!!.blocks)
        val resolved = ImportedMusicResolver.resolve(f.parser, saved(f))
        assertNotNull(resolved.allocation)
        assertSample(resolved.ram)
        val actual = NspcSequence.parse(resolved.ram, 6).channels[0].notes.single()
        assertEquals(12, actual.tick)
        assertEquals(0xA8, actual.noteValue)
    }

    @Test fun `note and instrument changes survive real save reopen native exports and ROM export with custom samples`() {
        val f = fixture()
        f.sound.recordPianoRollEdit("Change pitch")
        val song = f.sound.editingSong!!
        song.channels[0].notes.single().noteValue = 0xA8
        f.sound.notifySongChanged(song, f.editor)
        f.sound.recordPianoRollEdit("Change gain")
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[0].copy(gain = 0x20), f.parser, f.editor)
        assertEquals(0x6000, f.sound.pianoRollInstruments[0].sampleStartAddr)

        val file = File(directory, "music.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.absolutePath, null, false, {}))
        val reopened = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val sound = SoundEditorState().apply { selectTrack(track); openPianoRoll(f.parser, reopened) }
        assertEquals(0x6000, sound.pianoRollInstruments[0].sampleStartAddr)
        assertEquals(0x20, sound.pianoRollInstruments[0].gain)
        assertEquals(0xA8, sound.editingSong!!.channels[0].notes.single().noteValue)
        val snapshot = sound.buildPianoRollSpcSnapshotRam(f.parser, sound.editingSong!!)
        assertSample(snapshot)
        assertEquals(0x20, snapshot[0x6C03].toInt() and 255)
        val spc = sound.buildSpcSnapshotBytes(snapshot, track.playIndex, track.name)
        val spcRam = NativeSpcEmulator.readSpcRam(spc)
        assertSample(spcRam)
        assertEquals(0x20, spcRam[0x6C03].toInt() and 255)
        assertEquals(0xA8, NspcSequence.parse(spcRam, track.playIndex).channels[0].notes.single().noteValue)

        val raw = File(directory, "edited.nspc")
        MusicTrackInterchange.writeRawTransferBlocks(sound.pianoRollNativeExportBlocks(f.parser, sound.editingSong!!)!!, raw)
        val imported = MusicTrackInterchange.readNative(raw, 6)
        assertEquals(0xA8, imported.song.channels[0].notes.single().noteValue)
        val rawRam = ByteArray(65536).also { SpcData.applyTransferBlocks(it, imported.nativePayload!!.blocks) }
        assertSample(rawRam)
        assertEquals(0x20, rawRam[0x6C03].toInt() and 255)

        val rom = f.parser.copyRomData()
        assertEquals(1, MusicRomExporter(reopened.project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val exportedRam = SpcData.buildInitialSpcRam(exported).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, track.songSet))
        }
        assertContentEquals(snapshot, exportedRam, "Preview and ROM export must resolve the same imported bank and edits")
    }

    @Test fun `undo redo and a second import restore the matching sample source`() {
        val f = fixture()
        val first = saved(f).nativePayload!!
        val second = f.payload.copy(sourceFileName = "second.nspc")
        val nextSong = PianoRollPreviewLogic.deepCopySong(f.song).also { it.channels[0].notes.single().noteValue = 0xA9 }
        f.sound.applyImportedPianoRollSong(nextSong, f.instruments, second, f.editor, "second native")
        assertEquals("second.nspc", saved(f).nativePayload!!.sourceFileName)
        assertTrue(f.sound.undoPianoRollEdit(f.editor))
        assertEquals(first, saved(f).nativePayload)
        assertTrue(f.sound.undoPianoRollEdit(f.editor))
        assertNull(f.editor.getMusicEdit(track.songSet, track.playIndex), "Undoing the first import must restore stock assets too")
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
        assertEquals(first, saved(f).nativePayload)
        assertSample(f.sound.buildPianoRollSpcSnapshotRam(f.parser, f.sound.editingSong!!))
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
        assertEquals("second.nspc", saved(f).nativePayload!!.sourceFileName)
        f.sound.resetPianoRoll(f.parser, f.editor)
        assertNull(f.editor.getMusicEdit(track.songSet, track.playIndex))
    }

    @Test fun `sparse raw import does not erase untouched instrument slots`() {
        val f = fixture()
        val stock = SpcData.buildInitialSpcRam(f.parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(f.parser, track.songSet))
        }
        val resolved = ImportedMusicResolver.resolve(f.parser, saved(f))
        assertContentEquals(stock.copyOfRange(0x6C06, 0x6CFC), resolved.ram.copyOfRange(0x6C06, 0x6CFC))
        assertEquals(42, f.sound.pianoRollInstruments.size)
    }

    @Test fun `an inherited instrument slot can be explicitly cleared through the shared owner`() {
        val f = fixture()
        val inherited = f.sound.pianoRollInstruments[24]
        assertTrue(PianoRollPreviewLogic.instrumentBytes(inherited).any { it.toInt() != 0 })
        f.sound.recordPianoRollEdit("Clear inherited instrument")
        f.sound.updatePianoRollInstrument(inherited.copy(srcn = 0, adsr1 = 0, adsr2 = 0, gain = 0, pitchAdj = 0), f.parser, f.editor)
        val resolved = MusicBankResolver.resolve(f.parser, f.editor.project, track.songSet)
        assertContentEquals(ByteArray(6), resolved.ram.copyOfRange(inherited.tableAddr, inherited.tableAddr + 6))
        assertSample(resolved.ram)
        assertTrue(f.sound.undoPianoRollEdit(f.editor))
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(inherited),
            MusicBankResolver.resolve(f.parser, f.editor.project, track.songSet).ram.copyOfRange(inherited.tableAddr, inherited.tableAddr + 6))
    }

    @Test fun `failed relocation leaves retained samples and arrangement intact`() {
        val f = fixture()
        val before = saved(f)
        val edited = MusicEditConversion.toProjectEdit(track,
            PianoRollPreviewLogic.deepCopySong(f.song).also { it.channels[0].notes.single().noteValue = 0xAD },
            before.nativePayload)
        assertFailsWith<IllegalStateException> {
            ImportedMusicResolver.resolve(f.parser, edited,
                occupiedRanges = listOf(MusicSequenceBudget.SpcRange(0x1500, 0x10000)))
        }
        assertEquals(before, saved(f))
        assertSample(ImportedMusicResolver.resolve(f.parser, before).ram)
    }

    @Test fun `instrument-only edits preserve source sequence and native preview respects a muted gain`() = runBlocking {
        assumeTrue(NativeSpcEmulator.isAvailable())
        val f = fixture()
        val loud = assertNotNull(f.sound.renderEditedSong(f.parser, f.sound.editingSong!!))
        assertTrue(loud.maxOf { abs(it.toInt()) } > 100, "Imported short-note attacks must not be discarded")
        f.sound.recordPianoRollEdit("Mute gain")
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[0].copy(gain = 0), f.parser, f.editor)
        val muted = ImportedMusicResolver.resolve(f.parser, saved(f))
        assertNull(muted.allocation, "Instrument changes must not flatten imported sequences")
        assertSample(muted.ram)
        val native = assertNotNull(f.sound.renderEditedSong(f.parser, f.sound.editingSong!!))
        assertTrue(native.all { it.toInt() == 0 }, "Native silence must not fall back to an audible generic envelope")
        f.sound.loadTrackSamples(f.parser, f.editor)
        val waveform = assertNotNull(f.sound.currentWaveform)
        assertTrue(waveform.isNotEmpty())
        assertTrue(waveform.all { it.toInt() == 0 }, "A short silent waveform must not fall through to stock-track playback")
    }

    @Test fun `sibling tracks use retained native samples and shared overrides from the same bank`() {
        val f = fixture()
        f.sound.recordPianoRollEdit("Shared gain")
        f.sound.updatePianoRollInstrument(f.sound.pianoRollInstruments[0].copy(gain = 0x20), f.parser, f.editor)
        val sibling = SpcData.KNOWN_TRACKS.first { it.songSet == track.songSet && it.playIndex == 5 }
        val other = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, f.editor) }
        assertEquals(0x6000, other.pianoRollInstruments[0].sampleStartAddr)
        assertEquals(0x20, other.pianoRollInstruments[0].gain)
        val ram = other.buildPianoRollSpcSnapshotRam(f.parser, other.editingSong!!)
        assertSample(ram)
        assertEquals(0x20, ram[0x6C03].toInt() and 255)
        assertEquals(f.song.channels[0].notes.single().noteValue, NspcSequence.parse(ram, track.playIndex).channels[0].notes.single().noteValue)
        assertNotNull(other.pianoRollNativeExportBlocks(f.parser, other.editingSong!!), "A sibling's raw export must carry the shared custom sample bank")
    }
}
