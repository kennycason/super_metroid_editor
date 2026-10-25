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
    private fun legacy(f: Fixture, track: SpcData.TrackInfo, slot: Int, gain: Int) {
        val song = NspcSequence.parse(f.original, track.playIndex)
        val entries = NspcRenderer.readInstrumentTable(f.original).map { if (it.index == slot) it.copy(gain = gain) else it }
        f.editor.setMusicEdit(MusicEditConversion.key(3, track.playIndex), MusicEditConversion.toProjectEdit(track, song, entries))
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

    @Test fun `unambiguous legacy overrides coalesce and reset does not resurrect stale track snapshots after saving`() {
        val f = fixture()
        legacy(f, first, 28, 31)
        legacy(f, sibling, 29, 63)
        val inspection = MusicInstrumentBanks.inspect(f.parser, f.editor.project, 3)
        assertEquals(setOf(28, 29), inspection.instruments.keys)
        assertTrue(inspection.conflicts.isEmpty())
        val sound = open(f)
        assertEquals(63, sound.pianoRollInstruments[29].gain, "The first track's stale snapshot must not hide the sibling override")
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
        assertNotNull(editor.project.musicInstrumentBanks["03"], "An empty authoritative record prevents old overrides resurfacing")
        assertTrue(editor.project.musicInstrumentBanks["03"]!!.instruments.isEmpty())
        assertTrue(reopened.undoPianoRollEdit(editor))
        assertEquals(63, editor.project.musicInstrumentBanks["03"]!!.instruments[29]!!.gain)
    }

    @Test fun `legacy conflicts never choose a winner implicitly and choosing a bank version supports undo redo`() {
        val f = fixture()
        legacy(f, first, 28, 31)
        legacy(f, sibling, 28, 63)
        val sound = open(f)
        assertEquals(1, sound.instrumentBankConflicts.size)
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        val rom = f.parser.copyRomData()
        val error = assertFailsWith<IllegalArgumentException> { MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) }
        assertTrue(error.message!!.contains("Resolve shared instrument conflicts"))
        assertContentEquals(f.parser.copyRomData(), rom)
        sound.recordPianoRollEdit("Move a note")
        sound.editingSong!!.channels.first { it.notes.isNotEmpty() }.notes.first().tick += 1
        sound.notifySongChanged(sound.editingSong!!, f.editor)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty(), "Note undo must not mark conflicting legacy data as reconciled")
        val choice = sound.instrumentBankConflicts.single().choices.first { it.trackKey == "03:05" }
        sound.resolveInstrumentBankConflict(28, choice)
        assertTrue(sound.instrumentBankConflicts.isEmpty())
        assertEquals(31, bank(f).instruments[28]!!.gain)
        assertEquals(31, open(f, sibling).pianoRollInstruments[28].gain)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertEquals(1, sound.instrumentBankConflicts.size)
        assertTrue(sound.redoPianoRollEdit(f.editor))
        assertTrue(sound.instrumentBankConflicts.isEmpty())
        assertEquals(31, open(f, sibling).pianoRollInstruments[28].gain)
    }

    @Test fun `identical legacy edits coalesce and project bank records stay separate from other banks and names`() {
        val f = fixture()
        legacy(f, first, 28, 31)
        legacy(f, sibling, 28, 31)
        assertEquals(1, MusicInstrumentBanks.inspect(f.parser, f.editor.project, 3).instruments.size)
        assertTrue(MusicInstrumentBanks.inspect(f.parser, f.editor.project, 3).conflicts.isEmpty())
        assertTrue(MusicInstrumentBanks.inspect(f.parser, f.editor.project, 0x0F).instruments.isEmpty())
        open(f)
        f.editor.renameMusicInstrument(3, 28, "Shared lead")
        f.editor.setMusicInstrumentBank(3, MusicInstrumentBankEdit())
        assertTrue(MusicInstrumentBanks.inspect(f.parser, f.editor.project, 3).instruments.isEmpty())
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
        assertTrue(bank(f).instruments.isEmpty())
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertEquals(23, bank(f).instruments[28]!!.gain)
    }

    @Test fun `overlapping native sources surface a bank problem instead of silently choosing an import`() {
        val f = fixture()
        val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += NspcSequence.Note(0, 48, 0xA4) }
        val blocks = NspcSequence.encode(song, 5, conductorAddrOverride = 0x7000)
            .map { (at, data) -> SpcData.TransferBlock(at, data) }
        val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(blocks, 5, "source.nspc", "Raw N-SPC"))
        for (track in listOf(first, sibling)) {
            f.editor.setMusicEdit(MusicEditConversion.key(3, track.playIndex), MusicEditConversion.toProjectEdit(track, song, emptyList(), payload))
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

    @Test fun `multiple legacy conflicts commit atomically only after every slot has an explicit choice`() {
        val f = fixture()
        for ((track, gain) in listOf(first to 31, sibling to 63)) {
            legacy(f, track, 28, gain)
            val edit = f.editor.getMusicEdit(3, track.playIndex)!!
            f.editor.setMusicEdit(MusicEditConversion.key(3, track.playIndex), edit.copy(instruments = edit.instruments.map {
                if (it.index == 29) it.copy(gain = gain) else it
            }.toMutableList()))
        }
        val sound = open(f)
        val firstChoice = sound.instrumentBankConflicts.first { it.index == 28 }.choices.first { it.trackKey == "03:05" }
        sound.resolveInstrumentBankConflict(28, firstChoice)
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertEquals(listOf(29), sound.instrumentBankConflicts.map { it.index })
        assertFailsWith<IllegalArgumentException> { MusicBankResolver.resolve(f.parser, f.editor.project, 3) }
        val secondChoice = sound.instrumentBankConflicts.single().choices.first { it.trackKey == "03:06" }
        sound.resolveInstrumentBankConflict(29, secondChoice)
        assertEquals(31, bank(f).instruments[28]!!.gain)
        assertEquals(63, bank(f).instruments[29]!!.gain)
        assertTrue(sound.undoPianoRollEdit(f.editor))
        assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertEquals(2, sound.instrumentBankConflicts.size)
    }

    @Test fun `explicit editable bundle imports its shared settings rather than treating them as stale snapshots`() {
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
