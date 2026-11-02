package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sound.midi.*
import kotlin.test.*

class MidiVoicePackingTest {
    @TempDir lateinit var directory: File
    private fun read(events: List<Triple<Int, Int, ShortMessage>>, instruments: List<NspcRenderer.InstrumentEntry> = emptyList()): MusicTrackInterchange.MidiReadResult {
        val sequence = Sequence(Sequence.PPQ, 96)
        val track = sequence.createTrack()
        for ((tick, _, message) in events.sortedBy { it.first }) track.add(MidiEvent(message, tick.toLong()))
        val file = File(directory, "fixture.mid")
        MidiSystem.write(sequence, 1, file)
        return MusicTrackInterchange.readMidiWithReport(file, instruments)
    }
    private fun event(tick: Int, command: Int, channel: Int, pitch: Int, velocity: Int = 100) =
        Triple(tick, channel, ShortMessage().apply { setMessage(command, channel, pitch, velocity) })
    private fun on(tick: Int, channel: Int, pitch: Int) = event(tick, ShortMessage.NOTE_ON, channel, pitch)
    private fun off(tick: Int, channel: Int, pitch: Int) = event(tick, ShortMessage.NOTE_OFF, channel, pitch, 0)
    private fun notes(result: MusicTrackInterchange.MidiReadResult) = result.song.channels.flatMap { it.notes }
    private fun assertEncodable(result: MusicTrackInterchange.MidiReadResult) {
        for (channel in result.song.channels) assertTrue(channel.notes.zipWithNext().all { (a, b) -> a.endTick <= b.tick })
        val ram = ByteArray(65536)
        NspcSequence.encode(result.song, 5).forEach { (at, data) -> data.copyInto(ram, at) }
        assertEquals(notes(result).size, NspcSequence.parse(ram, 5).channels.sumOf { it.notes.size })
    }

    @Test fun `a midi chord uses separate native voices without changing timing`() {
        val result = read((60..63).flatMap { listOf(on(0, 0, it), off(300, 0, it)) })
        assertEquals(4, result.report.activeChannels)
        assertTrue(notes(result).all { it.tick == 0 && it.duration == 300 })
        assertEncodable(result)
    }

    @Test fun `original midi channel and raw pitch keep note offs isolated after clamping`() {
        val result = read(listOf(on(0, 0, 0), on(0, 8, 1), on(0, 0, 2), off(12, 8, 1), off(24, 0, 2), off(36, 0, 0)))
        assertEquals(listOf(12, 24, 36), notes(result).map { it.duration }.sorted())
        assertEncodable(result)
    }

    @Test fun `eight busy voices drop only excess new notes and reuse voices at exact note off`() {
        val events = (0..8).flatMap { listOf(on(0, 0, 60 + it), off(24, 0, 60 + it)) } + listOf(on(24, 0, 72), off(48, 0, 72))
        val result = read(events)
        assertEquals(9, notes(result).size)
        assertTrue(result.report.warnings.any { "dropped 1 note" in it })
        assertEquals(1, notes(result).count { it.tick == 24 })
        assertEncodable(result)
    }

    @Test fun `general midi programs select loaded native sounds rather than becoming instrument numbers`() {
        val candidates = listOf(24, 27).map { slot -> NspcRenderer.InstrumentEntry(24, 0x8F, 0xE0, 0x7F, 0x0001,
            slot, 0x6C00 + slot * 6, sampleStartAddr = 0x9000, brrByteLength = 9, brrLoops = true) }
        val result = read(listOf(event(0, ShortMessage.PROGRAM_CHANGE, 0, 120, 0), on(0, 0, 60), off(24, 0, 60)), candidates)
        assertTrue(notes(result).all { it.instrument in listOf(24, 27) })
        assertTrue(result.report.warnings.any { "mapped approximately" in it })
        assertEncodable(result)
    }

    @Test fun `unterminated notes end at file boundary and are disclosed`() {
        val result = read(listOf(on(0, 0, 60), event(72, ShortMessage.CONTROL_CHANGE, 0, 1, 10)))
        assertEquals(72, notes(result).single().duration)
        assertTrue(result.report.warnings.any { "without note-off" in it })
    }
}
