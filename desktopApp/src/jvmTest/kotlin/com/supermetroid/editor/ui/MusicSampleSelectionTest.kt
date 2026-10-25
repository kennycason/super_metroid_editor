package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicSampleSelectionTest {
    @TempDir lateinit var directory: File
    private val track = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
    private val sibling = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState)
    private fun fixture(): Fixture {
        val rom = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(rom.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(rom.absolutePath) }
        return Fixture(parser, editor, SoundEditorState().apply { selectTrack(track); openPianoRoll(parser, editor) })
    }

    @Test fun `choosing a recording edits only shared SRCN with one undo step and consistent save reopen export`() = runBlocking {
        val f = fixture()
        f.sound.loadPianoRollSampleCatalog()
        val before = f.sound.pianoRollInstruments.first { it.index >= 24 && it.nativePitch > 0 }
        val candidate = f.sound.pianoRollSamples.first { it.srcn < 24 && it.srcn != before.srcn }
        val song = PianoRollPreviewLogic.deepCopySong(f.sound.editingSong!!)
        val version = f.sound.pianoRollSampleVersion
        assertFalse(f.sound.choosePianoRollSample(before.index, 0x40))
        assertEquals(0, f.sound.pianoRollUndoDepth)
        assertTrue(f.sound.choosePianoRollSample(before.index, candidate.srcn))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        assertEquals(version, f.sound.pianoRollSampleVersion, "Changing an instrument must not decode every recording again")
        val actual = f.sound.pianoRollInstruments[before.index]
        assertEquals(candidate.srcn, actual.srcn)
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(before).drop(1).toByteArray(), PianoRollPreviewLogic.instrumentBytes(actual).drop(1).toByteArray())
        assertEquals(song, f.sound.editingSong)
        assertTrue(f.editor.project.musicEdits.isEmpty())
        assertFalse(f.sound.choosePianoRollSample(before.index, candidate.srcn))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        f.editor.renameMusicSample(3, candidate.resourceId, "Selected recording")
        val file = File(directory, "sample-choice.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val reopened = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, editor); loadPianoRollSampleCatalog() }
        assertEquals(candidate.srcn, reopened.pianoRollInstruments[before.index].srcn)
        assertEquals("Selected recording", editor.musicSampleNames(3)[candidate.resourceId])
        val preview = reopened.buildPianoRollSpcSnapshotRam(f.parser, reopened.editingSong!!)
        val rom = f.parser.copyRomData()
        assertEquals(1, MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom))
        val parser = RomParser(rom)
        val exported = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, 3)) }
        assertContentEquals(preview, exported)
        assertTrue(f.sound.undoPianoRollEdit(f.editor))
        assertEquals(before.srcn, f.sound.pianoRollInstruments[before.index].srcn)
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
        assertEquals(candidate.srcn, f.sound.pianoRollInstruments[before.index].srcn)
    }

    @Test fun `common source edits retain the existing export restriction and fail before ROM mutation`() = runBlocking {
        val f = fixture()
        f.sound.loadPianoRollSampleCatalog()
        val before = f.sound.pianoRollInstruments[0]
        val candidate = f.sound.pianoRollSamples.first { !it.isNoise && it.srcn != before.srcn }
        assertTrue(f.sound.choosePianoRollSample(0, candidate.srcn))
        val rom = f.parser.copyRomData()
        val unchanged = rom.copyOf()
        val failure = assertFailsWith<IllegalStateException> { MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) }
        assertTrue(failure.message.orEmpty().contains("common instrument"))
        assertContentEquals(unchanged, rom)
    }

    @Test fun `source names follow imported content and provenance includes sibling imports across undo and reset`() = runBlocking {
        val f = fixture()
        val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += NspcSequence.Note(0, 48, 0xA4, instrument = 24) }
        val source = NspcRenderer.InstrumentEntry(24, 0, 0, 0x60, 0x0001, 24, 0x6C90)
        val brr = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        fun payload(sample: ByteArray, file: String) = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(
            NspcSequence.encode(song, 5, conductorAddrOverride = 0x6500, sequenceEndAddr = 0x6900).map { (at, data) -> SpcData.TransferBlock(at, data) } + listOf(
                SpcData.TransferBlock(0x6C90, PianoRollPreviewLogic.instrumentBytes(source)),
                SpcData.TransferBlock(0x6D60, byteArrayOf(0, 0x60, 0, 0x60)), SpcData.TransferBlock(0x6000, sample)), 5, file, "Raw N-SPC"), song)
        f.sound.applyImportedPianoRollSong(song, emptyList(), payload(brr, "first.nspc"), f.editor, "first")
        assertFalse(f.sound.choosePianoRollSample(24, 0x85), "An out-of-date catalog cannot apply a stale source")
        f.sound.loadPianoRollSampleCatalog()
        val first = f.sound.pianoRollSamples.first { it.srcn == 24 }
        assertEquals(listOf("first.nspc"), first.importedFrom)
        f.editor.renameMusicSample(3, first.resourceId, "Custom loop")
        val other = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, f.editor); loadPianoRollSampleCatalog() }
        assertEquals(first, other.pianoRollSamples.first { it.srcn == 24 })
        val changed = brr.copyOf().also { it[1] = 0x67 }
        f.sound.applyImportedPianoRollSong(song, emptyList(), payload(changed, "second.nspc"), f.editor, "second")
        f.sound.loadPianoRollSampleCatalog()
        val second = f.sound.pianoRollSamples.first { it.srcn == 24 }
        assertNotEquals(first.resourceId, second.resourceId)
        assertNull(f.editor.musicSampleNames(3)[second.resourceId], "Reused slot/address must not inherit a different recording's label")
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); f.sound.loadPianoRollSampleCatalog()
        assertEquals(first.resourceId, f.sound.pianoRollSamples.first { it.srcn == 24 }.resourceId)
        assertEquals("Custom loop", f.editor.musicSampleNames(3)[first.resourceId])
        assertTrue(f.sound.redoPianoRollEdit(f.editor)); f.sound.loadPianoRollSampleCatalog()
        assertEquals(second.resourceId, f.sound.pianoRollSamples.first { it.srcn == 24 }.resourceId)
        f.sound.resetPianoRoll(f.parser, f.editor); f.sound.loadPianoRollSampleCatalog()
        assertTrue(f.sound.pianoRollSamples.none { "second.nspc" in it.importedFrom })
    }

    @Test fun `cancelled or stale background catalog loads cannot replace another bank`() = runBlocking {
        val f = fixture()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { f.sound.loadPianoRollSampleCatalog() }
        val other = SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 }
        f.sound.selectTrack(other); f.sound.openPianoRoll(f.parser, f.editor)
        pending.join()
        assertTrue(f.sound.pianoRollSamples.isEmpty())
        assertTrue(f.sound.isLoadingPianoRollSamples)
        f.sound.loadPianoRollSampleCatalog()
        val ram = MusicBankResolver.sourceRam(f.parser, f.editor.project, other.songSet)
        assertEquals(MusicSampleCatalog.read(ram).map { it.resourceId }, f.sound.pianoRollSamples.map { it.resourceId })
        assertFalse(f.sound.isLoadingPianoRollSamples)
        assertTrue(f.editor.project.musicEdits.isEmpty())
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
    }

    @Test fun `cancelled catalog decoding can be retried without leaving a stuck loading state`() = runBlocking {
        val f = fixture()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { f.sound.loadPianoRollSampleCatalog() }
        pending.cancel(); pending.join()
        assertFalse(f.sound.isLoadingPianoRollSamples)
        assertTrue(f.sound.pianoRollSamples.isEmpty())
        f.sound.loadPianoRollSampleCatalog()
        assertTrue(f.sound.pianoRollSamples.any { !it.isNoise })
        assertFalse(f.sound.isLoadingPianoRollSamples)
        assertNull(f.sound.pianoRollSamplesProblem)
    }
}
