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
    @Test fun `recording and noise candidate previews match committed native audio without editing while browsing`() = runBlocking {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val parser = TestRomHelper.requireRomParser()
        val devices = mutableListOf<DeferredSoundClip>()
        val state = SoundEditorState(SoundPlayer { DeferredSoundClip().also { devices += it }.clip })
        val editor = EditorState()
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 })
        state.openPianoRoll(parser, editor); state.loadPianoRollSampleCatalog()
        val before = state.pianoRollInstruments.first { it.index >= 24 && it.nativePitch > 0 && InstrumentDescriptions.playable(it) }
        val originalSong = PianoRollPreviewLogic.deepCopySong(state.editingSong!!)
        val note = note().copy(instrument = before.index)
        val recording = state.pianoRollSamples.first { !it.isNoise && it.srcn != before.srcn }
        for (srcn in listOf(recording.srcn, 0x85)) {
            val oldEntry = state.pianoRollInstruments[before.index]
            val history = state.pianoRollUndoDepth
            state.previewInstrument(parser, before.index, note, srcn)
            val preview = devices.last().audioBytes!!
            assertTrue(devices.last().started)
            assertTrue(preview.any { it.toInt() != 0 }, "The native candidate fixture must exercise audible audio, not only matching silence")
            assertEquals(srcn, state.instrumentPreviewSample)
            assertEquals(oldEntry, state.pianoRollInstruments[before.index])
            assertEquals(history, state.pianoRollUndoDepth)
            assertEquals(originalSong, state.editingSong)
            assertTrue(editor.project.musicEdits.isEmpty())
            assertEquals(null, state.currentWaveform)
            assertTrue(state.choosePianoRollSample(before.index, srcn))
            assertEquals(null, state.instrumentPreviewSample)
            state.previewInstrument(parser, before.index, note)
            assertContentEquals(preview, devices.last().audioBytes, "Candidate and committed source must use the same native envelope, tuning, and samples")
            assertEquals(history + 1, state.pianoRollUndoDepth)
        }
        state.stopInstrumentPreview()
        state.previewInstrument(parser, before.index, note, 0x40)
        assertEquals(null, state.instrumentPreviewIndex)
        assertEquals("Source is not available in this bank", state.instrumentPreviewMessage)
    }
    @Test fun `native preview reports playing until the accepted output actually completes`() = runBlocking {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val parser = TestRomHelper.requireRomParser()
        val device = DeferredSoundClip()
        val state = SoundEditorState(SoundPlayer { device.clip })
        state.setInstrumentPreviewLive(false)
        val editor = EditorState()
        state.selectTrack(SpcData.KNOWN_TRACKS.first { it.songSet == 0x18 && it.playIndex == 5 })
        state.openPianoRoll(parser, editor)
        assertTrue(state.pianoRollInstruments.count(InstrumentDescriptions::playable) > 3,
            "The loaded ROM bank has more sounds than the few rows visible at once")
        val song = PianoRollPreviewLogic.deepCopySong(state.editingSong!!)
        val note = song.channels.first { it.notes.isNotEmpty() }.notes.first()
        state.previewInstrument(parser, note.instrument, note)
        assertTrue(device.started)
        assertFalse(device.running)
        assertEquals(note.instrument, state.instrumentPreviewIndex)
        assertEquals("Playing · ${note.name}", state.instrumentPreviewMessage)
        device.complete()
        assertEquals(null, state.instrumentPreviewIndex)
        assertEquals("Preview finished · ${note.name}", state.instrumentPreviewMessage)
        assertEquals(song, state.editingSong)
        assertEquals(0, state.pianoRollUndoDepth)
        assertTrue(editor.project.musicEdits.isEmpty())
        state.stopInstrumentPreview()
    }

    private fun instrument(index: Int = 30) = NspcRenderer.InstrumentEntry(
        srcn = 0, adsr1 = 0, adsr2 = 0, gain = 0x60, pitchAdj = 0x0001,
        index = index, tableAddr = 0x6C00 + index * 6, sampleStartAddr = 0x9000, brrByteLength = 144, brrLoops = true
    )
    private fun note(duration: Int = 48) = NspcSequence.Note(0, duration, NspcSequence.nameToNote("C4"), instrument = 30)

    @Test fun `native shape shortcuts produce audible soft attack pluck and held sustain using the same recording`() {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte()).copyInto(ram, 0x9000)
        byteArrayOf(0, 0x90.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        val before = ram.copyOf()
        fun render(shape: NativeInstrumentSound.EnvelopeShape) = InstrumentAudition.render(InstrumentAudition.build(ram, NativeInstrumentSound.shape(instrument(), shape), note(120)))
        val hold = render(NativeInstrumentSound.EnvelopeShape.HOLD)
        val soft = render(NativeInstrumentSound.EnvelopeShape.SOFT)
        val pluck = render(NativeInstrumentSound.EnvelopeShape.PLUCK)
        fun energy(audio: ShortArray, from: Int, to: Int) = (from until to).sumOf { abs(audio[it].toInt()).toLong() }
        val onset = hold.indexOfFirst { abs(it.toInt()) > 100 }
        assertTrue(onset >= 0, "Native held shape must be audible")
        val earlyHold = energy(hold, onset, onset + 1000)
        val earlySoft = energy(soft, onset, onset + 1000)
        assertTrue(earlyHold > earlySoft * 2, "Early native attack energy: hold=$earlyHold, soft=$earlySoft")
        assertTrue(energy(hold, 16000, 22000) > 100000)
        assertTrue(energy(pluck, 16000, 22000) < energy(hold, 16000, 22000) / 100)
        assertContentEquals(before, ram)
    }

    @Test fun `native sound controls change audible attack sustain gain and musical tuning without replacing samples`() {
        assumeTrue(NativeSpcEmulator.isAvailable(), "Native SPC library unavailable")
        val parser = TestRomHelper.requireRomParser()
        val ram = SpcData.buildInitialSpcRam(parser)
        val brr = byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte())
        brr.copyInto(ram, 0x9000)
        byteArrayOf(0, 0x90.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        val original = ram.copyOf()
        val native = NativeInstrumentSound
        val fast = native.fade(native.sustain(native.attack(native.envelope(instrument(), true), 15), 7), 0)
        fun render(entry: NspcRenderer.InstrumentEntry) = InstrumentAudition.render(InstrumentAudition.build(ram, entry, note(120)))
        val held = render(fast)
        val slow = render(native.attack(fast, 0))
        fun energy(audio: ShortArray, from: Int, to: Int) = audio.sliceArray(from until to).sumOf { abs(it.toInt()).toLong() }
        assertTrue(energy(held, 1000, 4000) > energy(slow, 1000, 4000) * 4,
            "Native attack settings must remain audible, without preview normalization")
        val fading = render(native.fade(fast, 31))
        assertTrue(energy(held, 10000, 16000) > 100000)
        assertTrue(energy(fading, 10000, 16000) < energy(held, 10000, 16000) / 100,
            "Sustain fade happens while the note is held, not as an adjustable release")
        val fixed = native.gainValue(native.gainMode(native.envelope(fast, false), NativeInstrumentSound.GainMode.FIXED), 64)
        val fixedAudio = render(fixed)
        val quieter = render(native.gainValue(fixed, 16))
        assertTrue(energy(fixedAudio, 1000, 5000) > energy(quieter, 1000, 5000) * 3)
        val falling = render(native.gainValue(native.gainMode(fixed, NativeInstrumentSound.GainMode.FALL), 31))
        assertTrue(falling.all { it.toInt() == 0 }, "Falling gain really starts silent on key-on")
        val octave = render(native.tuning(fast, fast.nativePitch, 1200.0))
        fun crossings(audio: ShortArray) = (2000 until 5000).count { audio[it - 1] < 0 && audio[it] >= 0 }
        assertTrue(crossings(held) > 10)
        assertTrue(abs(crossings(octave) - crossings(held) * 2) <= 3,
            "+12 semitones must double native playback pitch: base=${crossings(held)}, octave=${crossings(octave)}")
        assertTrue(held.takeLast(8000).all { abs(it.toInt()) < 5 })
        assertContentEquals(original, ram)
    }

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
