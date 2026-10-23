package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstrumentAuditionTest {
    private fun instrument(index: Int = 30) = NspcRenderer.InstrumentEntry(
        srcn = 0, adsr1 = 0, adsr2 = 0, gain = 0x60, pitchAdj = 0x0001,
        index = index, tableAddr = 0x6C00 + index * 6, sampleStartAddr = 0x9000, brrByteLength = 144, brrLoops = true
    )
    private fun note(duration: Int = 48) = NspcSequence.Note(0, duration, NspcSequence.nameToNote("C4"), instrument = 30)

    @Test fun `audition keeps source RAM samples and note untouched and selects melodic instruments explicitly`() {
        val source = ByteArray(65536) { 0x55 }
        val original = source.copyOf()
        val note = note()
        val preview = InstrumentAudition.build(source, instrument(), note)
        val parsed = NspcSequence.parse(preview.ram, InstrumentAudition.PLAY_INDEX)
        assertContentEquals(original, source)
        assertContentEquals(source.copyOfRange(0x6D00, source.size), preview.ram.copyOfRange(0x6D00, source.size))
        assertEquals(1, parsed.channels.sumOf { it.notes.size })
        assertEquals(note, parsed.channels[0].notes.single())
        assertContentEquals(PianoRollPreviewLogic.instrumentBytes(instrument()), preview.ram.copyOfRange(0x6CB4, 0x6CBA))
    }

    @Test fun `long audition notes use ties and remain bounded instead of truncating at 127 ticks`() {
        val preview = InstrumentAudition.build(ByteArray(65536), instrument(), note(192))
        val parsed = NspcSequence.parse(preview.ram, InstrumentAudition.PLAY_INDEX)
        assertEquals(140, parsed.channels[0].notes.single().duration)
        assertTrue(preview.seconds <= 3)
    }

    @Test fun `audition allocates around imported samples inside its usual preview area`() {
        val ram = ByteArray(65536)
        val brr = ByteArray(9 * 32).also { it[it.size - 9] = 3 }
        brr.copyInto(ram, 0x5830)
        byteArrayOf(0x30, 0x58, 0x30, 0x58).copyInto(ram, 0x6D00)
        val original = ram.copyOf()
        val preview = InstrumentAudition.build(ram, instrument(), note())
        assertContentEquals(original, ram)
        assertContentEquals(brr, preview.ram.copyOfRange(0x5830, 0x5830 + brr.size))
        assertEquals(note(), NspcSequence.parse(preview.ram, InstrumentAudition.PLAY_INDEX).channels[0].notes.single())
    }

    @Test fun `bank names do not create music edits or trigger audio rendering`() {
        val state = EditorState()
        state.renameMusicInstrument(0x0F, 30, " Soft pulse ")
        state.renameMusicInstrument(0x18, 30, "Metallic lead")
        assertEquals("Soft pulse", state.musicInstrumentNames(0x0F)[30])
        assertEquals("Metallic lead", state.musicInstrumentNames(0x18)[30])
        assertTrue(state.project.musicEdits.isEmpty())
        assertTrue(state.project.patches.isEmpty())
        assertEquals(0, state.musicEditVersion)
        val version = state.musicInstrumentNameVersion
        state.renameMusicInstrument(0x18, 30, "Metallic lead")
        assertEquals(version, state.musicInstrumentNameVersion)
        state.renameMusicInstrument(0x0F, 30, " ")
        assertFalse(state.musicInstrumentNames(0x0F).containsKey(30))
    }

    @Test fun `stopping a pending audition prevents stale playback and leaves the arrangement untouched`() = runBlocking {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val parser = TestRomHelper.requireRomParser()
        val editor = EditorState()
        val state = SoundEditorState()
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 })
        state.openPianoRoll(parser, editor)
        val original = PianoRollPreviewLogic.deepCopySong(state.editingSong!!)
        val note = original.channels.first { it.notes.isNotEmpty() }.notes.first()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.previewInstrument(parser, note.instrument, note) }
        assertTrue(state.isInstrumentPreviewLoading)
        state.stopInstrumentPreview()
        pending.join()
        assertEquals(null, state.instrumentPreviewIndex)
        assertEquals("", state.instrumentPreviewMessage)
        assertFalse(state.isInstrumentPreviewLoading)
        assertFalse(state.isPlaying)
        assertEquals(original, state.editingSong)
        assertEquals(0, state.pianoRollUndoDepth)
        assertTrue(editor.project.musicEdits.isEmpty())
        assertEquals(null, state.currentWaveform)
    }

    @Test fun `native audition preserves short note attacks and gain comparisons and software tuning agrees`() {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val parser = TestRomHelper.requireRomParser()
        val ram = SpcData.buildInitialSpcRam(parser)
        // A periodic, filter-0 BRR block keeps this an engine pitch test rather
        // than a test of the encoder's predictive-filter behavior at a loop seam.
        val brr = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53,
            0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        brr.copyInto(ram, 0x9000)
        ram[0x6D00] = 0; ram[0x6D01] = 0x90.toByte()
        ram[0x6D02] = 0; ram[0x6D03] = 0x90.toByte()
        val quiet = instrument(0).copy(gain = 0x20)
        val loud = quiet.copy(gain = 0x60)
        val shortNote = note(12).copy(instrument = 0)
        val quietPreview = InstrumentAudition.render(InstrumentAudition.build(ram, quiet, shortNote))
        val loudSnapshot = InstrumentAudition.build(ram, loud, shortNote)
        val loudPreview = InstrumentAudition.render(loudSnapshot)
        fun peak(audio: ShortArray) = audio.take(8000).maxOf { abs(it.toInt()) }
        assertTrue(peak(quietPreview) > 100, "A short note must be audible before the old one-second warmup")
        assertTrue(peak(loudPreview) > peak(quietPreview) * 2, "Audition must preserve gain differences")
        assertTrue(loudPreview.drop(24000).maxOf { abs(it.toInt()) } < 100, "The note must release, not retrigger")

        val event = NspcRenderer.NoteEvent(0, 12, 0, shortNote.noteValue, 0, 1f)
        val software = NspcRenderer.renderToWav(listOf(event), loudSnapshot.ram, tempo = InstrumentAudition.TEMPO,
            maxSeconds = 1.0, normalize = false)
        // The SPC engine starts processing the play command on its own tick.
        // Compare equal sustained windows after each renderer's onset.
        fun crossings(audio: ShortArray): Int {
            val start = audio.indexOfFirst { abs(it.toInt()) > 100 } + 1000
            return (start until start + 2000).count { audio[it - 1] < 0 && audio[it] >= 0 }
        }
        assertTrue(crossings(loudPreview) > 10,
            "native crossings=${crossings(loudPreview)} software=${crossings(software)} " +
                "native onset=${loudPreview.indexOfFirst { abs(it.toInt()) > 100 }} " +
                "range=${loudPreview.take(8000).minOrNull()}..${loudPreview.take(8000).maxOrNull()}")
        assertTrue(abs(crossings(loudPreview) - crossings(software)) <= 2,
            "Fallback pitch must agree with native 8.8 tuning: native=${crossings(loudPreview)} software=${crossings(software)}")

        // A melodic slot in the former percussion-shortcut range must sound the
        // same when selected in the arrangement and in the browser.
        val melodic = loud.copy(index = 30, tableAddr = 0x6CB4)
        val melodicNote = note(48)
        val isolatedRam = InstrumentAudition.build(ram, melodic, melodicNote)
        val isolated = InstrumentAudition.render(isolatedRam)
        val song = NspcSequence.Song(tempo = InstrumentAudition.TEMPO)
        song.channels[0].notes += melodicNote
        song.channels[0].commands += listOf(
            NspcSequence.ControlCommand(0, 0xE5, intArrayOf(0xC0)),
            NspcSequence.ControlCommand(0, 0xED, intArrayOf(0xFF)),
            NspcSequence.ControlCommand(0, 0xE1, intArrayOf(10)),
            NspcSequence.ControlCommand(0, 0xF6, intArrayOf())
        )
        val arrangementRam = isolatedRam.ram.copyOf()
        NspcSequence.encode(song, InstrumentAudition.PLAY_INDEX, conductorAddrOverride = 0x5830).forEach { (at, data) ->
            data.copyInto(arrangementRam, at)
        }
        val arrangement = NativeSpcEmulator().use { emulator ->
            emulator.loadFromRam(arrangementRam, emptyList(), InstrumentAudition.PLAY_INDEX, playbackWarmupSeconds = 0)
            emulator.renderMono(1)
        }
        assertContentEquals(isolated.copyOf(12000), arrangement.copyOf(12000),
            "Selecting melodic instrument 30 must not trigger a percussion note or a different slot")
    }
}
