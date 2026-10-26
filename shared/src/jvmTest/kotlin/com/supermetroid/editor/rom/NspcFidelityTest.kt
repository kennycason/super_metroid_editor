package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.*

class NspcFidelityTest {
    private fun raw(vararg streams: IntArray): ByteArray = ByteArray(65536).also { ram ->
        fun word(at: Int, value: Int) { ram[at] = value.toByte(); ram[at + 1] = (value ushr 8).toByte() }
        word(0x581E + 10, 0x5830)
        for ((index, stream) in streams.withIndex()) {
            val table = 0x5840 + index * 16
            val data = 0x5900 + index * 256
            word(0x5830 + index * 2, table); word(table, data)
            stream.map(Int::toByte).toByteArray().copyInto(ram, data)
        }
    }

    @Test fun `long notes retain exact lengths and timed commands without retriggering`() {
        for (duration in listOf(1, 127, 128, 254, 300, 1024)) {
            val song = NspcSequence.Song()
            song.channels[0].notes += NspcSequence.Note(0, duration, 0xA4, velocity = 11, quantize = 6, instrument = 24)
            song.channels[0].notes += NspcSequence.Note(duration + 24, 24, 0xA5, instrument = 25)
            if (duration > 128) song.channels[0].commands += NspcSequence.ControlCommand(64, 0xE1, intArrayOf(10))
            val ram = ByteArray(65536)
            NspcSequence.encode(song, 5).forEach { (at, data) -> data.copyInto(ram, at) }
            val parsed = NspcSequence.parse(ram, 5)
            assertEquals(song.channels[0].notes, parsed.channels[0].notes, "duration=$duration")
            if (duration > 128) assertEquals(64, parsed.channels[0].commands.single { it.command == 0xE1 }.tick)
            assertEquals(duration, song.channels[0].notes.first().duration, "Encoding must not edit input")
        }
    }

    @Test fun `ties after silence cannot extend an unrelated earlier note`() {
        val song = NspcSequence.parse(raw(intArrayOf(24, 0x7F, 0x94, 12, 0xC9, 24, 0xC8, 0x98, 0)), 5)
        assertEquals(listOf(24, 24), song.channels[0].notes.map { it.duration })
        assertEquals(listOf(0, 60), song.channels[0].notes.map { it.tick })
    }

    @Test fun `percussion plays C4 with the global base and channel state persists across blocks`() {
        val ram = raw(intArrayOf(0xFA, 24, 0xEA, 12, 12, 0x6B, 0xCA, 0), intArrayOf(0xCB, 0x94, 0))
        val notes = NspcSequence.parse(ram, 5).channels[0].notes
        assertEquals(listOf(0, 12, 24), notes.map { it.tick })
        assertEquals(listOf(12, 12, 12), notes.map { it.duration })
        assertEquals(listOf(24, 25, 25), notes.map { it.instrument })
        assertEquals(listOf(0xB0, 0xB0, 0xA0), notes.map { it.noteValue })
        assertTrue(notes.all { it.velocity == 11 && it.quantize == 6 })
        val encoded = ram.copyOf()
        NspcSequence.encode(NspcSequence.parse(ram, 5), 5).forEach { (at, data) -> data.copyInto(encoded, at) }
        assertEquals(notes, NspcSequence.parse(encoded, 5).channels[0].notes)
    }

    @Test fun `global percussion base applies to later voices in native channel order`() {
        val ram = raw(intArrayOf(0xFA, 24, 12, 0x7F, 0xCA, 0))
        ram[0x5842] = 0; ram[0x5843] = 0x5A
        intArrayOf(12, 0x7F, 0xCC, 0).map(Int::toByte).toByteArray().copyInto(ram, 0x5A00)
        val song = NspcSequence.parse(ram, 5)
        assertEquals(26, song.channels[1].notes.single().instrument)
        assertEquals(0xA4, song.channels[1].notes.single().noteValue)
    }

    @Test fun `global and channel transpose are not doubled by editable export`() {
        val ram = raw(intArrayOf(0xE9, 2, 0xEA, 12, 24, 0x7F, 0x94, 0xEA, 0xF4, 0x98, 0))
        val before = NspcSequence.parse(ram, 5)
        NspcSequence.encode(before, 5).forEach { (at, data) -> data.copyInto(ram, at) }
        assertEquals(before.channels[0].notes, NspcSequence.parse(ram, 5).channels[0].notes)
    }

    @Test fun `overlaps fail explicitly instead of moving later notes in time`() {
        val song = NspcSequence.Song()
        song.channels[0].notes += NspcSequence.Note(0, 48, 0xA4)
        song.channels[0].notes += NspcSequence.Note(12, 24, 0xA5)
        assertTrue(assertFailsWith<IllegalArgumentException> { NspcSequence.encode(song, 5) }.message.orEmpty().contains("overlapping"))
    }

    @Test fun `approximate renderer shares held-note percussion and cross-block timing`() {
        val ram = raw(intArrayOf(0xFA, 24, 127, 0x7F, 0xCA, 127, 0xC8, 0), intArrayOf(24, 0xCB, 0))
        val events = NspcRenderer.parseSequence(ram, 5)
        assertEquals(listOf(0, 254), events.map { it.tickStart })
        assertEquals(listOf(254, 24), events.map { it.tickDuration })
        assertEquals(listOf(24, 25), events.map { it.instrumentIdx })
    }
}
