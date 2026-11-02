package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SoundPresetAuditionTest {
    private data class Fixture(val parser: RomParser, val editor: EditorState, val sound: SoundEditorState,
        val target: SampleBankPreview.Target, val clips: MutableList<DeferredSoundClip>, val audition: SoundPresetAudition)
    private fun fixture(): Fixture {
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()
        val sound = SoundEditorState().apply {
            selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 3 && it.playIndex == 5 }); openPianoRoll(parser, editor)
        }
        val target = SampleBankPreview.Target(3, NspcSequence.Note(0, 96, NspcSequence.nameToNote("E4"), instrument = 24),
            "Bank", "Current sound", MusicBankTransitions.snapshot(editor.project), sound.selectedTrackId)
        val clips = mutableListOf<DeferredSoundClip>()
        val audition = SoundPresetAudition(parser, target, 36, SoundPlayer { DeferredSoundClip().also { clips += it }.clip })
        val preset = SoundPresets.capture(parser, MusicBankResolver.sourceRam(parser, editor.project, 3), 24, "Silent preset")
            .copy(adsr1 = 0, gain = 0)
        audition.select(preset)
        return Fixture(parser, editor, sound, target, clips, audition)
    }

    @Test fun `preset piano and current comparison use the same note and never edit project history`() = runBlocking {
        val f = fixture()
        val before = MusicBankTransitions.snapshot(f.editor.project)
        val song = f.sound.editingSong
        val version = f.editor.musicEditVersion
        assertTrue(f.clips.isEmpty()); assertFalse(f.audition.active)
        f.audition.changePitch(NspcSequence.nameToNote("F#3")); f.audition.play()
        assertTrue(f.clips.single().audioBytes!!.all { it == 0.toByte() }); assertTrue(f.clips.single().looping)
        f.audition.compare(true)
        assertFalse(f.clips.single().closed, "Continue playing until the replacement render is ready")
        f.audition.refresh()
        val ram = MusicBankResolver.sourceRam(f.parser, f.target.project, 3)
        val expected = InstrumentAudition.render(InstrumentAudition.build(ram, f.sound.pianoRollInstruments[24], f.target.note.copy(noteValue = f.audition.pitch)))
        assertContentEquals(pcmToBytes(expected), f.clips.last().audioBytes)
        assertTrue(expected.any { it != 0.toShort() }); assertTrue(f.audition.message.contains("Current sound · F#3"))
        f.audition.compare(false); f.audition.refresh()
        assertTrue(f.clips.last().audioBytes!!.all { it == 0.toByte() })
        assertEquals(before.musicEdits, f.editor.project.musicEdits); assertEquals(before.musicInstrumentBanks, f.editor.project.musicInstrumentBanks)
        assertEquals(version, f.editor.musicEditVersion); assertEquals(0, f.sound.pianoRollUndoDepth)
        assertSame(song, f.sound.editingSong); assertEquals("E4", f.target.note.name)
        f.audition.stop(); assertTrue(f.clips.all { it.closed })
    }

    @Test fun `stop discards pending audio and selection changes cannot restart a stopped session`() = runBlocking {
        val f = fixture()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { f.audition.play() }
        f.audition.stop(); pending.join()
        assertTrue(f.clips.isEmpty()); assertFalse(f.audition.busy)
        f.audition.compare(true); f.audition.refresh(); assertTrue(f.clips.isEmpty())
        f.audition.setLiveEnabled(false); f.audition.play(); assertFalse(f.clips.single().looping)
        f.clips.single().complete(); assertFalse(f.audition.active)
        f.audition.select(f.audition.preset!!.copy(name = "Another preset")); f.audition.refresh()
        assertEquals(1, f.clips.size)
        f.audition.play(); f.audition.compare(true)
        val stale = launch(start = CoroutineStart.UNDISPATCHED) { f.audition.refresh() }
        f.audition.compare(false); stale.join(); assertEquals(2, f.clips.size)
        f.audition.refresh(); assertTrue(f.clips.last().audioBytes!!.all { it == 0.toByte() })
        f.audition.stop(); f.audition.refresh(); assertEquals(3, f.clips.size)
    }
}
