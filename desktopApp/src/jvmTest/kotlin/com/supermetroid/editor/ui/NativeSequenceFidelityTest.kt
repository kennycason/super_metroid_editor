package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeSequenceFidelityTest {
    @Test fun `long editable note matches hand authored native ties without retriggering`() {
        assertTrue(NativeSpcEmulator.isAvailable(), "Native SPC runtime is required")
        val ram = SpcData.buildInitialSpcRam(TestRomHelper.requireRomParser())
        byteArrayOf(0xB3.toByte(), 0x03, 0x57, 0x77, 0x53, 0x0D, 0xB9.toByte(), 0x99.toByte(), 0xBD.toByte()).copyInto(ram, 0x9000)
        byteArrayOf(0, 0x90.toByte(), 0, 0x90.toByte()).copyInto(ram, 0x6D00)
        byteArrayOf(0, 0, 0, 0x60, 1, 0).copyInto(ram, 0x6C90)
        val song = NspcSequence.Song()
        song.channels[0].notes += NspcSequence.Note(0, 300, 0xA4, instrument = 24)
        song.channels[0].notes += NspcSequence.Note(324, 24, 0xA5, instrument = 24)
        NspcSequence.encode(song, 5).forEach { (at, data) -> data.copyInto(ram, at) }
        fun word(data: ByteArray, at: Int) = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
        val channel = word(ram, word(ram, word(ram, 0x5828)))
        val oracle = ram.copyOf()
        val expected = intArrayOf(0xE7, 36, 0xE0, 24, 127, 0x7F, 0xA4, 0xC8, 46, 0x7F, 0xC8, 24, 0xC9, 0xA5, 0)
            .map(Int::toByte).toByteArray()
        assertContentEquals(expected, ram.copyOfRange(channel, channel + expected.size))
        expected.copyInto(oracle, channel)
        fun render(data: ByteArray) = NativeSpcEmulator().use {
            it.loadFromRam(data, emptyList(), 5, playbackWarmupSeconds = 0); it.renderMono(6)
        }
        val actual = render(ram)
        assertTrue(actual.sliceArray(90000 until 120000).any { it.toInt() != 0 }, "The note must remain audible beyond the first 127 ticks")
        assertTrue(render(oracle).contentEquals(actual), "Native output must match the hand-authored tied-note stream")
    }
}
