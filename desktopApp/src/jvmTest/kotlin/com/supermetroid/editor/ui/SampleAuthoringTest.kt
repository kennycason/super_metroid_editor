package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.*
import javax.sound.sampled.*
import kotlin.test.*

class SampleAuthoringTest {
    @TempDir lateinit var directory: File

    @Test fun `all waveform presets are bounded deterministic and encode native loop flags`() {
        for (shape in SampleAuthoring.Shape.entries) for (period in listOf(32, 128, 512)) {
            val draft = SampleAuthoring.generate(shape, period)
            assertEquals(draft, SampleAuthoring.generate(shape, period))
            val encoded = SampleAuthoring.encode(draft)
            assertEquals(draft.pcm.size / 16 * 9, encoded.brr.size)
            assertEquals(if (shape == SampleAuthoring.Shape.NOISE) 1 else 3, encoded.brr[encoded.brr.size - 9].toInt() and 3)
            assertEquals(0, encoded.brr[0].toInt() and 12, "A loop must not depend on the previous iteration's predictor history")
            assertTrue(encoded.pcm.any { it.toInt() != 0 })
        }
    }

    @Test fun `trim loop offset and gain are encoded without changing source pcm`() {
        val input = SampleAuthoring.generate(SampleAuthoring.Shape.SINE).copy(trimStart = 16, trimEnd = 128, loopStart = 64, gainPercent = 25)
        val before = input.pcm.toList()
        val encoded = SampleAuthoring.encode(input)
        assertEquals(112, encoded.pcm.size)
        assertEquals(48, encoded.loopFrame)
        assertEquals(27, encoded.loopByteOffset)
        assertEquals(0, encoded.brr[27].toInt() and 12)
        assertTrue(encoded.pcm.maxOf { kotlin.math.abs(it.toInt()) } < 7000)
        assertEquals(before, input.pcm)
    }

    @Test fun `full level sixteen bit waveforms retain polarity and headroom through the fifteen bit codec`() {
        val draft = SampleAuthoring.generate(SampleAuthoring.Shape.SINE).copy(gainPercent = 100)
        val decoded = SampleAuthoring.encode(draft).pcm
        val rms = kotlin.math.sqrt(decoded.indices.sumOf { val error = decoded[it].toDouble() - draft.pcm[it]; error * error } / decoded.size)
        assertTrue(rms < 1000, "16-bit scale must be restored without wrapped harmonics: RMS error $rms")
        assertTrue(decoded.max() > 22000 && decoded.min() < -22000)
    }

    @Test fun `local sample drafts reopen losslessly including source generator trim and loop settings`() {
        val draft = SampleAuthoring.generate(SampleAuthoring.Shape.TRIANGLE, 256).copy(name = "Soft triangle", trimStart = 16, trimEnd = 512, loopStart = 256)
        val file = File(directory, "triangle.smedit-sample")
        SampleAuthoring.save(draft, file)
        val reopened = SampleAuthoring.open(file)
        assertEquals(draft, reopened)
        assertContentEquals(SampleAuthoring.encode(draft).brr, SampleAuthoring.encode(reopened).brr)
        assertTrue(file.readText().contains("loopStart"))
    }

    @Test fun `native loop entry decodes identically after repeated predictor histories`() {
        val draft = SampleAuthoring.generate(SampleAuthoring.Shape.SINE).copy(trimStart = 16, trimEnd = 128, loopStart = 64)
        val encoded = SampleAuthoring.encode(draft)
        val loop = encoded.brr.copyOfRange(encoded.loopByteOffset!!, encoded.brr.size)
        val intro = encoded.brr.copyOf().also { it[it.size - 9] = (it[it.size - 9].toInt() and 0xFE).toByte() }
        val middle = loop.copyOf().also { it[it.size - 9] = (it[it.size - 9].toInt() and 0xFE).toByte() }
        val decoded = SpcData.decodeBrr(intro + middle + loop, 0)
        val start = encoded.loopFrame!!
        val length = encoded.pcm.size - start
        assertContentEquals(decoded.copyOfRange(start, start + length), decoded.copyOfRange(start + length, start + length * 2))
        assertContentEquals(decoded.copyOfRange(start, start + length), decoded.copyOfRange(start + length * 2, start + length * 3))
    }

    @Test fun `maximum drafts reopen and invalid saves cannot overwrite existing assets`() {
        val draft = SampleDraft("Full recording", List(SampleAuthoring.MAX_FRAMES) { Short.MIN_VALUE.toInt() })
        val file = File(directory, "maximum.smedit-sample")
        SampleAuthoring.save(draft, file)
        assertEquals(draft, SampleAuthoring.open(file))
        val before = file.readBytes()
        assertFailsWith<IllegalArgumentException> { SampleAuthoring.save(draft.copy(loopStart = 1), file) }
        assertContentEquals(before, file.readBytes())
        val sample = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
        val brr = File(directory, "sample.brr")
        SampleAuthoring.exportBrr(sample, brr)
        assertContentEquals(sample.brr, brr.readBytes())
        assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
    }

    @Test fun `invalid formats bounds pcm levels and loop positions fail without truncation`() {
        val draft = SampleAuthoring.generate(SampleAuthoring.Shape.SINE)
        for (bad in listOf(draft.copy(version = 2), draft.copy(pcm = emptyList()), draft.copy(pcm = List(16) { 40000 }),
            draft.copy(pcm = List(SampleAuthoring.MAX_FRAMES + 16) { 0 }), draft.copy(trimStart = 1), draft.copy(trimEnd = 0),
            draft.copy(loopStart = draft.pcm.size), draft.copy(loopStart = 3), draft.copy(gainPercent = 101))) {
            assertFailsWith<IllegalArgumentException> { SampleAuthoring.encode(bad) }
        }
    }

    private fun wav(file: File, frames: Int, rate: Int, channels: Int) {
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val bytes = pcmToBytes(ShortArray(frames * channels) { if (it % 2 == 0) 6000 else 3000 })
        AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()).use { AudioSystem.write(it, AudioFileFormat.Type.WAVE, file) }
    }

    @Test fun `wav import converts stereo and sample rate then pads only the last native block`() {
        val file = File(directory, "stereo.wav")
        wav(file, 441, 44100, 2)
        val draft = SampleAuthoring.readWav(file)
        assertEquals(320, draft.pcm.size)
        assertNull(draft.loopStart)
        assertNull(draft.generator)
        assertTrue(draft.pcm.any { it != 0 })
        assertEquals(180, SampleAuthoring.encode(draft).brr.size)
    }

    @Test fun `long wav import refuses to silently discard the tail`() {
        val file = File(directory, "too-long.wav")
        wav(file, SampleAuthoring.MAX_FRAMES + 100, 32000, 1)
        assertTrue(assertFailsWith<IllegalArgumentException> { SampleAuthoring.readWav(file) }.message.orEmpty().contains("trim"))
    }

    @Test fun `native encoded sample preview keeps source ram untouched and plays at recorded rate`() {
        assertTrue(NativeSpcEmulator.isAvailable())
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        val original = ram.copyOf()
        val encoded = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
        val preview = SampleAuthoring.preview(ram, encoded)
        assertContentEquals(original, ram)
        assertContentEquals(encoded.brr, preview.ram.copyOfRange(0x8000, 0x8000 + encoded.brr.size))
        val audio = InstrumentAudition.render(preview)
        assertTrue(audio.sliceArray(10000 until 20000).maxOf { kotlin.math.abs(it.toInt()) } > 1000)
        val crossings = (10001 until 20000).count { audio[it - 1] < 0 && audio[it] >= 0 }
        assertTrue(crossings in 74..83, "250 Hz recorded-rate preview, got $crossings crossings in 0.3125 s")
    }

    @Test fun `editing while encoding discards stale results and rejects invalid drafts`() = runBlocking<Unit> {
        val state = SampleWorkbenchState()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { state.prepare() }
        state.update(state.draft.copy(gainPercent = 0))
        first.join()
        assertNull(state.encoded)
        state.prepare()
        assertTrue(state.encoded!!.pcm.all { it.toInt() == 0 })
        state.update(state.draft.copy(loopStart = 1)); state.prepare()
        assertNull(state.encoded)
        assertNotNull(state.problem)
    }

    @Test fun `native preview accepts asynchronous output and stop invalidates stale renders`() = runBlocking {
        val devices = mutableListOf<DeferredSoundClip>()
        val state = SampleWorkbenchState(SoundPlayer { DeferredSoundClip().also { devices += it }.clip })
        state.prepare()
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { state.preview(ram) }
        assertTrue(state.previewing)
        state.stop(); pending.join()
        assertTrue(devices.isEmpty())
        state.preview(ram)
        assertTrue(state.playing)
        assertEquals("Playing encoded BRR", state.message)
        assertFalse(devices.single().running)
        devices.single().complete()
        assertFalse(state.playing)
        assertEquals("Preview finished", state.message)
        state.stop()
    }
}
