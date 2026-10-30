package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class LiveAuditionTest {
    @Test fun `waveform edits keep the previous sample playing and automatically replace its audio`() = runBlocking {
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        state.prepare(); state.preview(ram)
        assertTrue(state.live); assertTrue(clips.single().looping)
        clips.single().complete()
        assertTrue(state.playing, "A live audition repeats until Stop")
        state.update(SampleAuthoring.generate(SampleAuthoring.Shape.TRIANGLE))
        assertTrue(state.playing)
        assertFalse(clips.single().closed, "Encoding must not interrupt the previous sound")
        state.prepare()
        assertEquals(2, clips.size)
        assertTrue(clips[0].closed); assertTrue(clips[1].looping)
        assertFalse(clips[0].audioBytes!!.contentEquals(clips[1].audioBytes!!))
        clips[0].complete()
        assertTrue(state.playing)
        state.stop()
        assertTrue(clips[1].closed)
        state.update(SampleAuthoring.generate(SampleAuthoring.Shape.SAW)); state.prepare()
        assertEquals(2, clips.size, "Edits after Stop must not resume playback")
        assertFalse(state.playing)
    }

    @Test fun `rapid sample edits discard stale audio and Stop cancels a pending replacement`() = runBlocking {
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        state.prepare(); state.preview(ram)
        state.update(state.draft.copy(gainPercent = 20))
        val stale = launch(start = CoroutineStart.UNDISPATCHED) { state.prepare() }
        state.update(state.draft.copy(gainPercent = 40)); stale.join()
        assertEquals(1, clips.size)
        assertFalse(clips.single().closed)
        state.prepare()
        assertEquals(2, clips.size)
        state.update(state.draft.copy(gainPercent = 60))
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.prepare() }
        state.stop(); pending.join()
        assertEquals(2, clips.size)
        assertTrue(clips.last().closed)
        assertFalse(state.playing); assertFalse(state.previewing)
    }

    @Test fun `invalid sample edits retain the last valid audio and toggling Live stops the session`() = runBlocking {
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        state.prepare(); state.preview(ram)
        state.update(state.draft.copy(loopStart = 1)); state.prepare()
        assertNotNull(state.problem)
        assertTrue(state.playing); assertFalse(clips.single().closed)
        state.update(state.draft.copy(loopStart = 0)); state.prepare()
        assertEquals(2, clips.size)
        state.setLiveEnabled(false)
        assertFalse(state.playing); assertTrue(clips.last().closed)
        state.preview(ram)
        assertFalse(clips.last().looping)
        clips.last().complete()
        assertFalse(state.playing)
        state.update(state.draft.copy(gainPercent = 30)); state.prepare()
        assertEquals(3, clips.size)
        state.stop()
    }

    @Test fun `sound property edits refresh the audition without adding history or replacing the arrangement`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 })
        state.openPianoRoll(parser, editor)
        val entry = state.pianoRollInstruments.first { it.index >= 24 && InstrumentDescriptions.playable(it) }
        val note = NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = entry.index)
        val arrangement = state.editingSong
        state.previewInstrument(parser, entry.index, note)
        assertTrue(clips.single().looping)
        clips.single().complete()
        assertEquals(entry.index, state.instrumentPreviewIndex)
        assertTrue(state.commitPianoRollInstrument(state.selectedTrackId, entry.copy(adsr1 = 0, gain = 0), parser, editor))
        assertFalse(clips.single().closed)
        assertEquals(entry.index, state.instrumentPreviewIndex)
        assertTrue(state.isInstrumentPreviewLoading)
        state.refreshInstrumentPreview()
        assertEquals(2, clips.size)
        assertTrue(clips[0].closed); assertTrue(clips[1].looping)
        assertFalse(clips[0].audioBytes!!.contentEquals(clips[1].audioBytes!!))
        assertSame(arrangement, state.editingSong)
        assertEquals(1, state.pianoRollUndoDepth)
        assertTrue(state.undoPianoRollEdit(editor)); state.refreshInstrumentPreview()
        assertEquals(3, clips.size)
        assertContentEquals(clips[0].audioBytes, clips[2].audioBytes)
        assertTrue(state.redoPianoRollEdit(editor)); state.refreshInstrumentPreview()
        assertEquals(4, clips.size)
        assertContentEquals(clips[1].audioBytes, clips[3].audioBytes)
        state.stopInstrumentPreview()
        state.refreshInstrumentPreview()
        assertEquals(4, clips.size)
        assertNull(state.instrumentPreviewIndex)
    }

    @Test fun `Stop and track changes prevent pending sound edits from restarting audio`() = runBlocking {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()
        val clips = mutableListOf<DeferredSoundClip>()
        val state = SoundEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 })
        state.openPianoRoll(parser, editor)
        val entry = state.pianoRollInstruments.first { InstrumentDescriptions.playable(it) }
        val note = NspcSequence.Note(0, 120, NspcSequence.nameToNote("C4"), instrument = entry.index)
        state.previewInstrument(parser, entry.index, note)
        state.updatePianoRollInstrument(entry.copy(gain = 1), parser, editor)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.refreshInstrumentPreview() }
        state.stopInstrumentPreview(); pending.join()
        assertEquals(1, clips.size)
        assertTrue(clips.single().closed); assertNull(state.instrumentPreviewIndex)
        state.previewInstrument(parser, entry.index, note)
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.id != state.selectedTrackId })
        state.refreshInstrumentPreview()
        assertEquals(2, clips.size)
        assertTrue(clips.last().closed); assertNull(state.instrumentPreviewIndex)
    }
}
