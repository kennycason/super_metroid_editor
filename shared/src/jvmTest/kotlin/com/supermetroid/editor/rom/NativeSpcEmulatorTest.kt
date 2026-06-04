package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NativeSpcEmulatorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    @Test
    fun `JNA library loads successfully`() {
        val available = NativeSpcEmulator.isAvailable()
        println("libspc available via JNA: $available")
        Assumptions.assumeTrue(available, "libspc not available (submodule not built?)")
    }

    @Test
    fun `render title screen via JNA`() {
        Assumptions.assumeTrue(NativeSpcEmulator.isAvailable(), "libspc not available")
        val parser = loadTestRom()
        Assumptions.assumeTrue(parser != null, "Test ROM not found")
        parser!!

        val baseRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, 0x03)

        NativeSpcEmulator().use { emu ->
            emu.loadFromRam(baseRam, blocks, playIndex = 5)
            val stereo = emu.renderStereo(seconds = 5)
            val mono = emu.renderMono(seconds = 5)

            println("Stereo samples: ${stereo.size}")
            println("Mono samples: ${mono.size}")
            val peak = stereo.maxOf { abs(it.toInt()) }
            println("Stereo peak: $peak")
            assert(stereo.size > 100000) { "Should render significant audio" }
            assert(peak > 200) { "Should have audible signal" }
        }
        println("OK - JNA render works")
    }

    @Test
    fun `render multiple songs and compare`() {
        Assumptions.assumeTrue(NativeSpcEmulator.isAvailable(), "libspc not available")
        val parser = loadTestRom()
        Assumptions.assumeTrue(parser != null, "Test ROM not found")
        parser!!
        val baseRam = SpcData.buildInitialSpcRam(parser)

        data class TestSong(val songSet: Int, val playIndex: Int, val name: String)
        val songs = listOf(
            TestSong(0x03, 5, "title_intro"),
            TestSong(0x0F, 5, "green_brinstar"),
            TestSong(0x24, 5, "boss_fight"),
        )

        val peaks = mutableListOf<Int>()
        for (song in songs) {
            val blocks = SpcData.findSongSetTransferData(parser, song.songSet)
            NativeSpcEmulator().use { emu ->
                emu.loadFromRam(baseRam, blocks, song.playIndex)
                val mono = emu.renderMono(5)
                val peak = if (mono.isNotEmpty()) mono.maxOf { abs(it.toInt()) } else 0
                peaks.add(peak)
                println("${song.name}: ${mono.size} samples, peak=$peak")
            }
        }

        assert(peaks.distinct().size == peaks.size) {
            "All songs should have distinct peaks, got: $peaks"
        }
        println("OK - All songs produce distinct audio")
    }

    @Test
    fun `explicit song blocks render like prepatched song RAM`() {
        Assumptions.assumeTrue(NativeSpcEmulator.isAvailable(), "libspc not available")
        val parser = loadTestRom()
        Assumptions.assumeTrue(parser != null, "Test ROM not found")
        parser!!

        val baseRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, 0x0F)
        val prepatchedRam = baseRam.copyOf()
        SpcData.applyTransferBlocks(prepatchedRam, blocks)

        val explicitBlocks = NativeSpcEmulator().use { emu ->
            emu.loadFromRam(baseRam, blocks, playIndex = 5)
            emu.renderMono(5)
        }
        val prepatched = NativeSpcEmulator().use { emu ->
            emu.loadFromRam(prepatchedRam, emptyList(), playIndex = 5)
            emu.renderMono(5)
        }

        val compareSamples = minOf(explicitBlocks.size, prepatched.size, NativeSpcEmulator.SAMPLE_RATE * 5)
        val maxDelta = (0 until compareSamples).maxOf { i -> abs(explicitBlocks[i].toInt() - prepatched[i].toInt()) }
        assertTrue(maxDelta <= 1, "Explicit song blocks should match prepatched RAM; maxDelta=$maxDelta")
    }

    @Test
    fun `title screen parse re-encode preserves native waveform envelope`() {
        Assumptions.assumeTrue(NativeSpcEmulator.isAvailable(), "libspc not available")
        val parser = loadTestRom()
        Assumptions.assumeTrue(parser != null, "Test ROM not found")
        parser!!

        val songSet = 0x03
        val playIndex = 5
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, songSet)
        val songRam = baseRam.copyOf()
        SpcData.applyTransferBlocks(songRam, blocks)
        val song = NspcSequence.parse(songRam, playIndex)
        val writes = NspcSequence.encode(song, playIndex, songRam, failOnOverflow = true)
        val patchBlocks = writes.map { (addr, data) -> SpcData.TransferBlock(addr, data) }

        val original = renderNativeMono(baseRam, blocks, playIndex, seconds = 8)
        val reencoded = renderNativeMono(baseRam, blocks + patchBlocks, playIndex, seconds = 8)
        val error = normalizedEnvelopeError(original, reencoded)
        val originalPeak = peak(original)
        val reencodedPeak = peak(reencoded)

        assertTrue(reencodedPeak > 200, "Re-encoded title screen should not be silent; peak=$reencodedPeak")
        assertTrue(
            error < 0.40,
            "Re-encoded title screen waveform envelope diverged too much: " +
                "error=$error originalPeak=$originalPeak reencodedPeak=$reencodedPeak"
        )
    }

    @Test
    fun `adding a note on an unused title screen channel keeps later waveform stable`() {
        Assumptions.assumeTrue(NativeSpcEmulator.isAvailable(), "libspc not available")
        val parser = loadTestRom()
        Assumptions.assumeTrue(parser != null, "Test ROM not found")
        parser!!

        val songSet = 0x03
        val playIndex = 5
        val baseRam = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, songSet)
        val songRam = baseRam.copyOf()
        SpcData.applyTransferBlocks(songRam, blocks)
        val song = NspcSequence.parse(songRam, playIndex)
        val emptyChannel = song.channels.indexOfFirst { it.notes.isEmpty() }
        Assumptions.assumeTrue(emptyChannel >= 0, "Title screen has no unused channel for isolated add-note test")

        song.channels[emptyChannel].notes += NspcSequence.Note(
            tick = 0,
            duration = 48,
            noteValue = NspcSequence.nameToNote("C4"),
            velocity = 12,
            quantize = 7,
            instrument = song.channels.first { it.notes.isNotEmpty() }.notes.first().instrument
        )
        song.isModified = true

        val writes = NspcSequence.encode(song, playIndex, songRam, failOnOverflow = true)
        val patchBlocks = writes.map { (addr, data) -> SpcData.TransferBlock(addr, data) }
        val original = renderNativeMono(baseRam, blocks, playIndex, seconds = 8)
        val modified = renderNativeMono(baseRam, blocks + patchBlocks, playIndex, seconds = 8)
        val startAfterAddedNote = NativeSpcEmulator.SAMPLE_RATE * 3
        val laterError = normalizedEnvelopeError(
            original.copyOfRange(startAfterAddedNote, original.size),
            modified.copyOfRange(startAfterAddedNote, modified.size)
        )
        val originalPeak = peak(original)
        val modifiedPeak = peak(modified)

        assertTrue(modifiedPeak > 200, "Modified title screen should not be silent; peak=$modifiedPeak")
        assertTrue(
            laterError < 0.40,
            "A short added note changed the later waveform too much: " +
                "error=$laterError originalPeak=$originalPeak modifiedPeak=$modifiedPeak"
        )
    }

    private fun renderNativeMono(
        baseRam: ByteArray,
        blocks: List<SpcData.TransferBlock>,
        playIndex: Int,
        seconds: Int
    ): ShortArray =
        NativeSpcEmulator().use { emu ->
            emu.loadFromRam(baseRam, blocks, playIndex)
            emu.renderMono(seconds)
        }

    private fun normalizedEnvelopeError(a: ShortArray, b: ShortArray, windowSize: Int = 1024): Double {
        val aEnv = envelope(a, windowSize)
        val bEnv = envelope(b, windowSize)
        val count = minOf(aEnv.size, bEnv.size)
        if (count == 0) return 0.0
        var deltaSq = 0.0
        var baseSq = 0.0
        for (i in 0 until count) {
            val delta = (aEnv[i] - bEnv[i]).toDouble()
            deltaSq += delta * delta
            baseSq += aEnv[i].toDouble() * aEnv[i].toDouble()
        }
        return sqrt(deltaSq / count) / sqrt((baseSq / count).coerceAtLeast(1.0))
    }

    private fun envelope(samples: ShortArray, windowSize: Int): IntArray {
        if (samples.isEmpty()) return IntArray(0)
        val windows = (samples.size + windowSize - 1) / windowSize
        return IntArray(windows) { w ->
            val start = w * windowSize
            val end = minOf(samples.size, start + windowSize)
            var peak = 0
            for (i in start until end) {
                peak = maxOf(peak, abs(samples[i].toInt()))
            }
            peak
        }
    }

    private fun peak(samples: ShortArray): Int =
        if (samples.isEmpty()) 0 else samples.maxOf { abs(it.toInt()) }
}
