package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

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
}
