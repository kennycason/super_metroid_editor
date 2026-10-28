package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MusicInstrumentBankTest {
    @TempDir lateinit var directory: File
    private val first = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }
    private val sibling = SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 6 }
    private data class Fixture(val parser: RomParser, val editor: EditorState, val original: ByteArray)
    private fun fixture(): Fixture {
        val rom = File(directory, "base.sfc").apply { writeBytes(TestRomHelper.requireRomBytes()) }
        val parser = RomParser(rom.readBytes())
        val editor = EditorState().apply { testMode = true; initForRom(rom.absolutePath) }
        val ram = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, 3)) }
        return Fixture(parser, editor, ram)
    }
    private fun open(f: Fixture, track: SpcData.TrackInfo = first) = SoundEditorState().apply { selectTrack(track); openPianoRoll(f.parser, f.editor) }
    private fun bank(f: Fixture) = assertNotNull(f.editor.project.musicInstrumentBanks["03"])
    private fun setBank(f: Fixture, vararg gains: Pair<Int, Int>) {
        val entries = NspcRenderer.readInstrumentTable(f.original)
        f.editor.setMusicInstrumentBank(3, MusicInstrumentBankEdit(gains.associate { (slot, gain) ->
            slot to MusicInstrumentBanks.entry(entries[slot].copy(gain = gain))
        }.toMutableMap()))
    }

    @Test fun `envelope shape shortcut is one shared undoable bank edit and reopens and exports as native bytes`() {
        val f = fixture()
        val sound = open(f)
        val source = sound.pianoRollInstruments.first { it.index >= 24 && InstrumentDescriptions.playable(it) }
        val shaped = NativeInstrumentSound.shape(source, NativeInstrumentSound.EnvelopeShape.SOFT)
        assertTrue(sound.commitPianoRollInstrument(first.id, shaped, f.parser, f.editor))
        assertEquals(1, sound.pianoRollUndoDepth)
        assertTrue(f.editor.project.musicEdits.isEmpty())
        assertEquals(source.srcn, sound.pianoRollInstruments[source.index].srcn)
        assertEquals(source.pitchAdj, sound.pianoRollInstruments[source.index].pitchAdj)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(source), PianoRollPreviewLogic.instrumentBytes(sound.pianoRollInstruments[source.index]))
        assertTrue(sound.redoPianoRollEdit(f.editor))
        val file = File(directory, "envelope-shape.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val reopened = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, editor) }
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(shaped), PianoRollPreviewLogic.instrumentBytes(reopened.pianoRollInstruments[source.index]))
        val rom = f.parser.copyRomData()
        assertEquals(1, MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom))
        val parser = RomParser(rom)
        val exported = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, 3)) }
        assertContentEquals(reopened.buildPianoRollSpcSnapshotRam(f.parser, reopened.editingSong!!), exported)
    }

    @Test fun `late instrument gestures cannot edit a different track or a closed inspector`() {
        val f = fixture()
        val sound = open(f)
        val source = sound.pianoRollInstruments.first { it.index >= 24 && InstrumentDescriptions.playable(it) }
        val shaped = NativeInstrumentSound.shape(source, NativeInstrumentSound.EnvelopeShape.SOFT)
        assertFalse(sound.commitPianoRollInstrument(first.id, source, f.parser, f.editor))
        assertEquals(0, sound.pianoRollUndoDepth)
        val other = SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 }
        sound.selectTrack(other); sound.openPianoRoll(f.parser, f.editor)
        assertFalse(sound.commitPianoRollInstrument(first.id, shaped, f.parser, f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertEquals(0, sound.pianoRollUndoDepth)
        sound.closePianoRoll()
        assertFalse(sound.commitPianoRollInstrument(other.id, shaped, f.parser, f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
    }

    @Test fun `native envelope and musical tuning edits keep their source reference through undo save reopen and export`() {
        val f = fixture()
        val sound = open(f)
        val source = sound.pianoRollSourceInstruments.first { it.index >= 24 && it.nativePitch in 2..32767 && InstrumentDescriptions.playable(it) }
        val slot = source.index
        val shaped = NativeInstrumentSound.tuning(
            NativeInstrumentSound.fade(NativeInstrumentSound.sustain(NativeInstrumentSound.attack(source, 4), 3), 15), source.nativePitch, 1200.0)
        sound.recordPianoRollEdit("Shape sound")
        sound.updatePianoRollInstrument(shaped, f.parser, f.editor)
        assertEquals(1, sound.pianoRollUndoDepth)
        assertEquals(source, sound.pianoRollSourceInstruments[slot])
        assertEquals(shaped.nativePitch, sound.pianoRollInstruments[slot].nativePitch)
        assertEquals(1200.0, NativeInstrumentSound.tuningCents(sound.pianoRollInstruments[slot], source.nativePitch))
        assertTrue(f.editor.project.musicEdits.isEmpty())
        val file = File(directory, "shaped.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val reopened = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, editor) }
        assertEquals(source, reopened.pianoRollSourceInstruments[slot])
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(shaped), PianoRollPreviewLogic.instrumentBytes(reopened.pianoRollInstruments[slot]))
        val preview = reopened.buildPianoRollSpcSnapshotRam(f.parser, reopened.editingSong!!)
        val rom = f.parser.copyRomData()
        assertEquals(1, MusicRomExporter(editor.project, {}).applyMusicEditsToRom(rom))
        val parser = RomParser(rom)
        val exported = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, 3)) }
        assertContentEquals(preview, exported)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertEquals(source, sound.pianoRollSourceInstruments[slot])
        assertEquals(source, sound.pianoRollInstruments[slot])
        assertTrue(sound.redoPianoRollEdit(f.editor))
        assertEquals(source, sound.pianoRollSourceInstruments[slot])
        assertEquals(shaped.nativePitch, sound.pianoRollInstruments[slot].nativePitch)
    }

    @Test fun `instrument-only edits are bank-owned shared on reopen and exported once without flattening tracks`() {
        val f = fixture()
        val sound = open(f)
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty(), "Browsing must not create empty bank edits")
        val before = sound.pianoRollInstruments[28]
        val changed = before.copy(gain = (before.gain + 37) and 255)
        sound.recordPianoRollEdit("Shared gain")
        sound.updatePianoRollInstrument(changed, f.parser, f.editor)
        assertEquals(changed.gain, bank(f).instruments[28]!!.gain)
        assertTrue(f.editor.project.musicEdits.isEmpty(), "Shared edits must not create a fake arrangement edit")
        assertTrue(f.editor.hasMusicEdit(3, 5))
        assertTrue(f.editor.hasMusicEdit(3, 6))
        assertFalse(f.editor.hasMusicEdit(0x0F, 5))
        val other = open(f, sibling)
        assertEquals(changed.gain, other.pianoRollInstruments[28].gain)
        assertEquals(listOf(first.name, sibling.name), other.instrumentBankTracks)
        val preview = other.buildPianoRollSpcSnapshotRam(f.parser, other.editingSong!!)
        val resolved = MusicBankResolver.resolve(f.parser, f.editor.project, 3)
        assertTrue(resolved.writes.keys.all { it in 0x6C00 until 0x6CFC })
        val rom = f.parser.copyRomData()
        assertEquals(1, MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom))
        val exported = RomParser(rom)
        val ram = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 3)) }
        assertContentEquals(preview, ram)
        for (track in listOf(first, sibling)) assertEquals(NspcSequence.parse(f.original, track.playIndex), NspcSequence.parse(ram, track.playIndex))
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertEquals(before.gain, open(f, sibling).pianoRollInstruments[28].gain)
        assertTrue(sound.redoPianoRollEdit(f.editor))
        assertEquals(changed.gain, open(f, sibling).pianoRollInstruments[28].gain)
    }

    @Test fun `bank settings survive save reopen and resetting the last override removes the bank record`() {
        val f = fixture()
        setBank(f, 28 to 31, 29 to 63)
        assertEquals(setOf(28, 29), MusicInstrumentBanks.instruments(f.editor.project, 3).keys)
        val sound = open(f)
        assertEquals(63, sound.pianoRollInstruments[29].gain)
        sound.resetSharedInstrument(28)
        assertEquals(setOf(29), bank(f).instruments.keys)
        val stockGain = NspcRenderer.readInstrumentTable(f.original)[28].gain
        assertEquals(stockGain, open(f, sibling).pianoRollInstruments[28].gain)
        val file = File(directory, "bank.smedit")
        assertTrue(ProjectFileService.saveProject(f.editor.project, file.path, null, false, {}))
        val editor = EditorState().apply { testMode = true; initForProject(file, ProjectFileService.loadProject(file)) }
        val reopened = SoundEditorState().apply { selectTrack(sibling); openPianoRoll(f.parser, editor) }
        assertEquals(stockGain, reopened.pianoRollInstruments[28].gain)
        assertEquals(63, reopened.pianoRollInstruments[29].gain)
        reopened.resetSharedInstrument(29)
        assertFalse(editor.project.musicInstrumentBanks.containsKey("03"), "Default sounds need no saved bank record")
        assertTrue(editor.project.musicEdits.isEmpty())
        assertTrue(reopened.undoPianoRollEdit(editor))
        assertEquals(63, editor.project.musicInstrumentBanks["03"]!!.instruments[29]!!.gain)
        assertTrue(reopened.redoPianoRollEdit(editor))
        assertFalse(editor.project.musicInstrumentBanks.containsKey("03"))
        assertTrue(ProjectFileService.saveProject(editor.project, file.path, null, false, {}))
        assertTrue(ProjectFileService.loadProject(file).musicInstrumentBanks.isEmpty())
    }

    @Test fun `note edits and undo redo never create instrument bank records`() {
        val f = fixture()
        val sound = open(f)
        sound.recordPianoRollEdit("Move note")
        val song = PianoRollPreviewLogic.deepCopySong(sound.editingSong!!)
        song.channels.first { it.notes.isNotEmpty() }.notes.first().tick += 1
        sound.notifySongChanged(song, f.editor)
        assertNotNull(f.editor.getMusicEdit(3, 5))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertTrue(f.editor.project.musicEdits.isEmpty())
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(sound.redoPianoRollEdit(f.editor))
        assertNotNull(f.editor.getMusicEdit(3, 5))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
    }

    @Test fun `invalid bank settings block export before modifying ROM data`() {
        val f = fixture()
        setBank(f, 28 to 31)
        val bank = bank(f)
        bank.instruments[28] = bank.instruments.getValue(28).copy(tableAddr = 0x6000)
        val sound = open(f)
        assertTrue(assertNotNull(sound.instrumentBankProblem).contains("Invalid shared instrument slot"))
        val rom = f.parser.copyRomData()
        val error = assertFailsWith<IllegalArgumentException> { MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) }
        assertTrue(error.message!!.contains("Invalid shared instrument slot"))
        assertContentEquals(f.parser.copyRomData(), rom)
    }

    @Test fun `bank settings stay separate from other banks and display names`() {
        val f = fixture()
        setBank(f, 28 to 31)
        assertEquals(1, MusicInstrumentBanks.instruments(f.editor.project, 3).size)
        assertTrue(MusicInstrumentBanks.instruments(f.editor.project, 0x0F).isEmpty())
        open(f)
        f.editor.renameMusicInstrument(3, 28, "Shared lead")
        f.editor.setMusicInstrumentBank(3, MusicInstrumentBankEdit())
        assertTrue(MusicInstrumentBanks.instruments(f.editor.project, 3).isEmpty())
        assertFalse(f.editor.project.musicInstrumentBanks.containsKey("03"))
        assertEquals("Shared lead", f.editor.musicInstrumentNames(3)[28])
    }

    @Test fun `track reset preserves shared overrides while reset sound has its own undo step`() {
        val f = fixture()
        val sound = open(f)
        sound.recordPianoRollEdit("Gain")
        sound.updatePianoRollInstrument(sound.pianoRollInstruments[28].copy(gain = 23), f.parser, f.editor)
        sound.resetPianoRoll(f.parser, f.editor)
        assertEquals(23, bank(f).instruments[28]!!.gain)
        assertEquals(23, sound.pianoRollInstruments[28].gain)
        sound.resetSharedInstrument(28)
        assertFalse(f.editor.project.musicInstrumentBanks.containsKey("03"))
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertEquals(23, bank(f).instruments[28]!!.gain)
    }

    @Test fun `overlapping native sources surface a bank problem instead of silently choosing an import`() {
        val f = fixture()
        val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += NspcSequence.Note(0, 48, 0xA4) }
        val blocks = NspcSequence.encode(song, 5, conductorAddrOverride = 0x7000)
            .map { (at, data) -> SpcData.TransferBlock(at, data) }
        val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, 5, "source.nspc", "Raw N-SPC"), song)
        for (track in listOf(first, sibling)) {
            f.editor.setMusicEdit(MusicEditConversion.key(3, track.playIndex), MusicEditConversion.toProjectEdit(track, song, payload))
        }
        val sound = open(f)
        assertTrue(assertNotNull(sound.instrumentBankProblem).contains("overlaps"))
        val rom = f.parser.copyRomData()
        assertFailsWith<IllegalArgumentException> { MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) }
        assertContentEquals(f.parser.copyRomData(), rom)
        sound.resetPianoRoll(f.parser, f.editor)
        assertNull(sound.instrumentBankProblem)
        assertNotNull(f.editor.getMusicEdit(3, 6))
    }

    @Test fun `explicit editable bundle imports shared settings with undo redo`() {
        val f = fixture()
        val sound = open(f)
        sound.recordPianoRollEdit("Original gain")
        sound.updatePianoRollInstrument(sound.pianoRollInstruments[28].copy(gain = 23), f.parser, f.editor)
        val importedSong = NspcSequence.parse(f.original, 5).also {
            it.channels.first { channel -> channel.notes.isNotEmpty() }.notes.first().noteValue = 0xA8
        }
        val importedEntries = NspcRenderer.readInstrumentTable(f.original).map { if (it.index == 28) it.copy(gain = 71) else it }
        val bundle = File(directory, "shared.nspc")
        MusicTrackInterchange.writeNspcTrack(importedSong, importedEntries, 3, 5, bundle)
        val decoded = MusicTrackInterchange.readNative(bundle, 5)
        assertNull(decoded.nativePayload)
        sound.applyImportedPianoRollSong(decoded.song, decoded.instruments, null, f.editor, "bundle")
        assertEquals(71, bank(f).instruments[28]!!.gain)
        assertEquals(71, open(f, sibling).pianoRollInstruments[28].gain)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertEquals(23, bank(f).instruments[28]!!.gain)
        assertTrue(sound.redoPianoRollEdit(f.editor))
        assertEquals(71, bank(f).instruments[28]!!.gain)
    }
}
