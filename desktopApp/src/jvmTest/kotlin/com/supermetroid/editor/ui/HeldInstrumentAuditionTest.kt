package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class HeldInstrumentAuditionTest {
    @Test fun `quick key releases cancel pending native playback and never edit notes or history`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val outputs = mutableListOf<HeldSoundPlayerTest.Output>()
        val state = SoundEditorState(heldInstrumentPlayer = HeldSoundPlayer { HeldSoundPlayerTest.Output().also { outputs += it } })
        val editor = EditorState()
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }); state.openPianoRoll(parser, editor)
        val note = NspcSequence.Note(0, 48, NspcSequence.nameToNote("C4"), instrument = 24)
        val before = MusicBankTransitions.snapshot(editor.project)
        val song = state.editingSong
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.previewInstrument(parser, 24, note, held = true) }
        state.releaseInstrumentKey(24, note.noteValue); pending.join()
        assertTrue(outputs.isEmpty()); assertNull(state.instrumentPreviewIndex)
        val held = launch(start = CoroutineStart.UNDISPATCHED) { state.previewInstrument(parser, 24, note, held = true) }
        withTimeout(5000) { while (outputs.firstOrNull()?.writes?.isEmpty() != false) delay(1) }
        state.releaseInstrumentKey(24, note.noteValue + 2)
        assertEquals(24, state.instrumentPreviewIndex, "A previous key's late release cannot stop this note")
        state.releaseInstrumentKey(24, note.noteValue); outputs.single().advance.release(100)
        withTimeout(5000) { held.join() }
        assertTrue(outputs.single().closed); assertTrue(outputs.single().finished)
        assertEquals(before.musicInstrumentBanks, editor.project.musicInstrumentBanks); assertEquals(before.musicEdits, editor.project.musicEdits)
        assertSame(song, state.editingSong); assertEquals(0, state.pianoRollUndoDepth)
        assertEquals("C4", note.name)
    }

    @Test fun `changing tracks closes a held voice and preset held sessions release independently of the library`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val output = HeldSoundPlayerTest.Output()
        val state = SoundEditorState(heldInstrumentPlayer = HeldSoundPlayer { output })
        val editor = EditorState()
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }); state.openPianoRoll(parser, editor)
        val note = NspcSequence.Note(0, 48, NspcSequence.nameToNote("D4"), instrument = 24)
        val held = launch(start = CoroutineStart.UNDISPATCHED) { state.previewInstrument(parser, 24, note, held = true) }
        withTimeout(5000) { while (output.writes.isEmpty()) delay(1) }
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x0F && it.playIndex == 5 })
        withTimeout(5000) { held.join() }; assertTrue(output.closed); assertNull(state.instrumentPreviewIndex)
        val target = SampleBankPreview.Target(3, note, "Bank", "Sound", MusicBankTransitions.snapshot(editor.project))
        val preset = SoundPresets.capture(parser, MusicBankResolver.sourceRam(parser, editor.project, 3), 24, "Test")
        val presetOutput = HeldSoundPlayerTest.Output()
        val audition = SoundPresetAudition(parser, target, 36, heldPlayer = HeldSoundPlayer { presetOutput })
        audition.select(preset)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { audition.play(hold = true) }
        audition.releaseKey(note.noteValue); pending.join()
        assertFalse(presetOutput.opened)
        val playing = launch(start = CoroutineStart.UNDISPATCHED) { audition.play(hold = true) }
        withTimeout(5000) { while (presetOutput.writes.isEmpty()) delay(1) }
        audition.releaseKey(note.noteValue); presetOutput.advance.release(100)
        withTimeout(5000) { playing.join() }
        assertTrue(presetOutput.finished); assertTrue(presetOutput.closed); assertFalse(audition.active)
        assertTrue(editor.project.musicInstrumentBanks.isEmpty())
    }
}
