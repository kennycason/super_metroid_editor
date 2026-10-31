package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SoundBankManagementTest {
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState)
    private fun fixture(bank: Int = 3): Fixture {
        val parser = TestRomHelper.requireRomParser(); val editor = EditorState()
        val sound = SoundEditorState().apply { selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == bank && it.playIndex == 5 }); openPianoRoll(parser, editor) }
        return Fixture(parser, editor, sound)
    }
    private fun target(f: Fixture) = SampleBankPreview.Target(f.sound.currentSongSet,
        NspcSequence.Note(0, 96, NspcSequence.nameToNote("C4"), instrument = 24), "Bank", "Sound", MusicBankTransitions.snapshot(f.editor.project), f.sound.selectedTrackId)
    @Test fun `copying into an explicit slot preserves sample ownership arrangements and one undo transaction`() {
        val f = fixture()
        val old = PianoRollPreviewLogic.deepCopySong(f.sound.editingSong!!)
        val source = f.sound.pianoRollInstruments[24]
        val bank = SoundBankManagement.duplicate(f.parser, target(f), 24, 28)
        assertTrue(f.sound.manageSoundBank(target(f), bank, "Copy sound", f.parser, f.editor))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(source), PianoRollPreviewLogic.instrumentBytes(f.sound.pianoRollInstruments[28]))
        assertEquals(old, f.sound.editingSong)
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
    }
    @Test fun `unused project samples can be removed while all instrument users keep referenced resources protected`() {
        val f = fixture()
        for (shape in listOf(SampleAuthoring.Shape.SINE, SampleAuthoring.Shape.TRIANGLE)) {
            f.sound.useWorkbenchSample(MusicSampleBanks.plan(f.parser, target(f), SampleAuthoring.encode(SampleAuthoring.generate(shape)), shape.name), f.parser, f.editor)
        }
        val overview = SoundBankManagement.inspect(f.parser, target(f))
        val unused = overview.samples.single { it.users.isEmpty() }
        val used = overview.samples.single { it.users.isNotEmpty() }
        assertEquals(listOf(24), used.users)
        assertFails { SoundBankManagement.removeSample(f.parser, target(f), used.srcn) }
        val before = MusicBankTransitions.snapshot(f.editor.project)
        val bank = SoundBankManagement.removeSample(f.parser, target(f), unused.srcn)
        assertTrue(f.sound.manageSoundBank(target(f), bank, "Remove unused sample", f.parser, f.editor))
        assertEquals(3, f.sound.pianoRollUndoDepth)
        val after = SoundBankManagement.inspect(f.parser, target(f))
        assertEquals(1, after.samples.size); assertTrue(after.freeBytes > overview.freeBytes)
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        assertTrue(f.sound.redoPianoRollEdit(f.editor)); assertEquals(1, SoundBankManagement.inspect(f.parser, target(f)).samples.size)
        val rom = f.parser.copyRomData(); assertTrue(MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) > 0)
    }
    @Test fun `new sounds use only zero empty slots outside music and effect references and export complete native data`() {
        val f = fixture(6)
        val overview = SoundBankManagement.inspect(f.parser, target(f))
        assertTrue(overview.emptySounds.isNotEmpty(), "Fixture must contain a native spare slot; found ${overview.instruments.map { it.index }}")
        val (slot, bank) = SoundBankManagement.create(f.parser, target(f))
        assertTrue(slot in overview.emptySounds)
        assertTrue(f.sound.manageSoundBank(target(f), bank, "Create sound", f.parser, f.editor))
        assertEquals(1, f.sound.pianoRollUndoDepth)
        val updated = f.sound.pianoRollInstruments.first { it.index == slot }
        assertTrue(InstrumentDescriptions.playable(updated)); assertTrue(updated.srcn >= 40)
        assertFalse(slot in SoundBankManagement.inspect(f.parser, target(f)).emptySounds)
        assertTrue(f.sound.undoPianoRollEdit(f.editor)); assertTrue(f.editor.project.musicInstrumentBanks.isEmpty())
        assertTrue(f.sound.redoPianoRollEdit(f.editor))
        val rom = f.parser.copyRomData(); assertTrue(MusicRomExporter(f.editor.project, {}).applyMusicEditsToRom(rom) > 0)
        val exported = RomParser(rom)
        val actual = SpcData.buildInitialSpcRam(exported).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(exported, 6)) }
        assertTrue(MusicBankResolver.resolve(f.parser, f.editor.project, 6).ram.contentEquals(actual))
    }
}
