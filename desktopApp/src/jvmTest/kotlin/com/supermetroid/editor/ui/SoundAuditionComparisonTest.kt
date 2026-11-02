package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class SoundAuditionComparisonTest {
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState,
        val clips: MutableList<DeferredSoundClip>)
    private fun fixture(bank: Int = 0x18): Fixture {
        val parser = TestRomHelper.requireRomParser()
        val clips = mutableListOf<DeferredSoundClip>()
        val editor = EditorState()
        val sound = SoundEditorState(SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        sound.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == bank && it.playIndex == 5 })
        sound.openPianoRoll(parser, editor)
        return Fixture(parser, editor, sound, clips)
    }
    private fun note(index: Int, pitch: String = "F#3") = NspcSequence.Note(0, 73,
        NspcSequence.nameToNote(pitch), velocity = 9, quantize = 5, instrument = index)
    private fun originalEntry(f: Fixture) = f.sound.pianoRollSourceInstruments.first {
        it.index >= 24 && it.nativePitch > 0 && InstrumentDescriptions.playable(it)
    }

    @Test fun `Original and Edited compare native audio at the same pitch velocity and duration without changing the project`() = runBlocking {
        val f = fixture()
        val source = originalEntry(f)
        val note = note(source.index)
        f.sound.commitPianoRollInstrument(f.sound.selectedTrackId, source.copy(adsr1 = 0, gain = 0), f.parser, f.editor)
        val before = MusicBankTransitions.snapshot(f.editor.project)
        val arrangement = f.sound.editingSong
        val version = f.editor.musicEditVersion
        val history = f.sound.pianoRollUndoDepth
        val expected = InstrumentAudition.render(InstrumentAudition.build(
            MusicBankResolver.sourceRam(f.parser, before, f.sound.currentSongSet, includeOverrides = false), source, note, arrangement!!.tempo))
        f.sound.previewInstrument(f.parser, source.index, note)
        val edited = f.clips.single().audioBytes!!
        assertTrue(edited.all { it == 0.toByte() })
        f.sound.compareOriginalInstrument(true)
        assertTrue(f.sound.instrumentAuditionOriginal)
        assertFalse(f.clips.single().closed, "Keep the prior clip playing while the other variant renders")
        f.sound.refreshInstrumentPreview()
        assertContentEquals(pcmToBytes(expected), f.clips.last().audioBytes)
        assertTrue(expected.any { it != 0.toShort() }, "The reference must be audible")
        assertTrue(f.clips.last().looping)
        assertTrue(f.sound.instrumentPreviewMessage.contains("Original · F#3"))
        f.sound.compareOriginalInstrument(false); f.sound.refreshInstrumentPreview()
        assertContentEquals(edited, f.clips.last().audioBytes)
        assertSame(arrangement, f.sound.editingSong)
        assertEquals(before.musicEdits, f.editor.project.musicEdits)
        assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        assertEquals(version, f.editor.musicEditVersion)
        assertEquals(history, f.sound.pianoRollUndoDepth)
        assertEquals("F#3", note.name)
        f.sound.stopInstrumentPreview()
    }

    @Test fun `Original remains the bank source after custom sample assignment and editing its current controls`() = runBlocking {
        val f = fixture(3)
        val source = originalEntry(f)
        val note = note(source.index, "C4")
        f.sound.previewInstrument(f.parser, source.index, note, original = true)
        val original = f.clips.last().audioBytes!!
        f.sound.stopInstrumentPreview()
        val target = SampleBankPreview.Target(3, note, "Title", "Sound", MusicBankTransitions.snapshot(f.editor.project), f.sound.selectedTrackId)
        val plan = MusicSampleBanks.plan(f.parser, target, SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.PULSE)), "Pulse")
        f.sound.useWorkbenchSample(plan, f.parser, f.editor)
        assertFalse(f.sound.instrumentAuditionOriginal, "Applying a new sample returns audition to Edited")
        f.sound.previewInstrument(f.parser, source.index, note, original = true)
        assertContentEquals(original, f.clips.last().audioBytes)
        val clip = f.clips.last()
        val refresh = f.sound.instrumentPreviewRefreshVersion
        val current = f.sound.pianoRollInstruments[source.index]
        f.sound.commitPianoRollInstrument(f.sound.selectedTrackId, current.copy(adsr1 = 0, gain = 0), f.parser, f.editor)
        assertFalse(clip.closed)
        assertEquals(refresh, f.sound.instrumentPreviewRefreshVersion, "Control edits must not alter the Original audition")
        f.sound.compareOriginalInstrument(false); f.sound.refreshInstrumentPreview()
        assertTrue(f.clips.last().audioBytes!!.all { it == 0.toByte() })
        assertEquals(plan.srcn, f.sound.pianoRollInstruments[source.index].srcn)
        assertEquals(2, f.sound.pianoRollUndoDepth, "Only sample assignment and the control edit create history")
        f.sound.stopInstrumentPreview()
    }

    @Test fun `comparison switching works for a one shot and Stop cancels a pending alternate render`() = runBlocking {
        val f = fixture()
        val source = originalEntry(f)
        f.sound.setInstrumentPreviewLive(false)
        f.sound.previewInstrument(f.parser, source.index, note(source.index))
        f.sound.compareOriginalInstrument(true)
        f.sound.refreshInstrumentPreview()
        assertEquals(2, f.clips.size)
        assertFalse(f.clips.last().looping)
        f.sound.compareOriginalInstrument(false)
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { f.sound.refreshInstrumentPreview() }
        f.sound.stopInstrumentPreview(); pending.join()
        assertEquals(2, f.clips.size)
        assertNull(f.sound.instrumentPreviewIndex)
        f.sound.compareOriginalInstrument(true); f.sound.refreshInstrumentPreview()
        assertEquals(2, f.clips.size, "Changing comparison while stopped cannot restart playback")
        f.sound.selectTrack(SpcData.KNOWN_TRACKS.first { it.id != f.sound.selectedTrackId })
        assertFalse(f.sound.instrumentAuditionOriginal)
    }

    @Test fun `Original uses an imported bank source instead of the ROM instrument or its current override`() = runBlocking {
        val f = fixture(3)
        val source = NspcRenderer.InstrumentEntry(24, 0, 0, 0x60, 0x0001, 24, 0x6C90)
        val song = NspcSequence.Song(tempo = 36).also { it.channels[0].notes += note(24, "C4") }
        val brr = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        val payload = MusicEditConversion.toProjectNativePayload(MusicTrackInterchange.NativePayload(
            NspcSequence.encode(song, 5, conductorAddrOverride = 0x6500, sequenceEndAddr = 0x6900).map { (at, data) -> SpcData.TransferBlock(at, data) } + listOf(
                SpcData.TransferBlock(0x6C90, PianoRollPreviewLogic.instrumentBytes(source)),
                SpcData.TransferBlock(0x6D60, byteArrayOf(0, 0x60, 0, 0x60)), SpcData.TransferBlock(0x6000, brr)),
            5, "reference.nspc", "Raw N-SPC"), song)
        f.sound.applyImportedPianoRollSong(song, emptyList(), payload, f.editor, "reference")
        val pitch = note(24, "D4")
        f.sound.previewInstrument(f.parser, 24, pitch)
        val importedAudio = f.clips.last().audioBytes!!
        f.sound.commitPianoRollInstrument(f.sound.selectedTrackId, f.sound.pianoRollInstruments[24].copy(gain = 0), f.parser, f.editor)
        f.sound.previewInstrument(f.parser, 24, pitch, original = true)
        assertContentEquals(importedAudio, f.clips.last().audioBytes)
        assertTrue(importedAudio.any { it != 0.toByte() })
        f.sound.stopInstrumentPreview()
    }

    @Test fun `rapid comparison changes discard an older Original render and retain the latest Edited choice`() = runBlocking {
        val f = fixture()
        val source = originalEntry(f)
        val note = note(source.index)
        f.sound.commitPianoRollInstrument(f.sound.selectedTrackId, source.copy(adsr1 = 0, gain = 0), f.parser, f.editor)
        f.sound.previewInstrument(f.parser, source.index, note)
        val edited = f.clips.single().audioBytes
        f.sound.compareOriginalInstrument(true)
        val stale = launch(start = CoroutineStart.UNDISPATCHED) { f.sound.refreshInstrumentPreview() }
        f.sound.compareOriginalInstrument(false)
        stale.join()
        assertEquals(1, f.clips.size)
        assertFalse(f.clips.single().closed)
        f.sound.refreshInstrumentPreview()
        assertEquals(2, f.clips.size)
        assertContentEquals(edited, f.clips.last().audioBytes)
        assertFalse(f.sound.instrumentAuditionOriginal)
        assertEquals(1, f.sound.pianoRollUndoDepth)
        f.sound.stopInstrumentPreview()
    }
}
