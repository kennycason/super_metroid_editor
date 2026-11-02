package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sound.midi.*
import kotlin.math.abs
import kotlin.test.*

class MusicExpressionTest {
    @TempDir lateinit var directory: File
    private fun song() = NspcSequence.Song(tempo = 36).also {
        it.channels[0].notes += NspcSequence.Note(0, 192, NspcSequence.nameToNote("C4"), instrument = 24)
    }
    @Test fun `typed automation retains source commands notes and native phase bits and round trips timed events`() {
        val original = song()
        original.channels[0].commands += NspcSequence.ControlCommand(0, 0xE0, intArrayOf(24))
        val before = PianoRollPreviewLogic.deepCopySong(original)
        var edited = original
        for (kind in MusicExpression.kinds) {
            val event = NspcSequence.ControlCommand(48, kind.opcode, kind.parameters.map { it.initial and 255 }.toIntArray())
            edited = MusicExpression.edit(edited, 0, null, event)
        }
        assertEquals(before, original)
        assertEquals(before.channels[0].notes, edited.channels[0].notes)
        assertEquals(before.channels[0].commands.first(), edited.channels[0].commands.first())
        val blocks = NspcSequence.encode(edited, 5, conductorAddrOverride = 0x6000)
        val ram = ByteArray(65536).also { bytes -> for ((at, data) in blocks) data.copyInto(bytes, at) }
        val parsed = NspcSequence.parse(ram, 5)
        assertEquals(edited.channels[0].notes, parsed.channels[0].notes)
        for (event in edited.channels[0].commands.drop(1)) assertTrue(event in parsed.channels[0].commands, "Missing ${event.command.toString(16)}")
        val pan = MusicExpression.kinds.first { it.opcode == 0xE1 }.parameters.single()
        assertEquals(0xC4, pan.store(0xCA, 4)); assertEquals(10, pan.value(0xCA))
        assertFails { MusicExpression.edit(edited, 0, 0, null) }
        assertFails { MusicExpression.validate(NspcSequence.ControlCommand(0, 0xF7, intArrayOf(3, 0, 0))) }
    }
    @Test fun `tempo automation drives preview duration seeking and cursor time including fades`() {
        val song = song()
        song.channels[0].commands += NspcSequence.ControlCommand(96, 0xE7, intArrayOf(72))
        val timing = MusicExpression.Timing(song)
        assertTrue(abs(timing.millis(96) - 1365) <= 1)
        assertTrue(abs(timing.millis(192) - 2048) <= 1)
        assertEquals(96, timing.tick(1366)); assertEquals(192, timing.tick(2048))
        assertEquals(listOf(96 to 72), timing.tempoChanges)
        song.channels[0].commands.clear(); song.channels[0].commands += NspcSequence.ControlCommand(0, 0xE8, intArrayOf(96, 72))
        val fade = MusicExpression.Timing(song)
        assertTrue(fade.millis(192) in 1600..1700)
        assertTrue(fade.tempoChanges.size > 10)
        assertTrue(fade.tempoChanges.zipWithNext().all { (a, b) -> a.second <= b.second })
    }
    @Test fun `MIDI sustain volume pan and tempo changes survive voice packing and native export`() {
        val seq = Sequence(Sequence.PPQ, 96)
        val track = seq.createTrack()
        fun midi(tick: Long, command: Int, a: Int, b: Int) = track.add(MidiEvent(ShortMessage().apply { setMessage(command, 0, a, b) }, tick))
        fun tempo(tick: Long, micros: Int) = track.add(MidiEvent(MetaMessage().apply { setMessage(0x51, byteArrayOf((micros ushr 16).toByte(), (micros ushr 8).toByte(), micros.toByte()), 3) }, tick))
        tempo(0, 1000000); tempo(96, 500000)
        midi(0, ShortMessage.CONTROL_CHANGE, 7, 64); midi(0, ShortMessage.CONTROL_CHANGE, 10, 0)
        midi(0, ShortMessage.CONTROL_CHANGE, 64, 127)
        midi(0, ShortMessage.NOTE_ON, 60, 100); midi(24, ShortMessage.NOTE_OFF, 60, 0)
        midi(48, ShortMessage.NOTE_ON, 64, 100); midi(72, ShortMessage.NOTE_OFF, 64, 0)
        midi(96, ShortMessage.CONTROL_CHANGE, 64, 0)
        val file = File(directory, "sustain.mid"); MidiSystem.write(seq, 1, file)
        val imported = MusicTrackInterchange.readMidiWithReport(file)
        val notes = imported.song.channels.flatMap { it.notes }
        assertEquals(2, notes.size); assertTrue(notes.all { it.endTick == 96 })
        val commands = imported.song.channels.flatMap { it.commands }
        assertTrue(commands.any { it.command == 0xED && it.params.single() == 128 })
        assertTrue(commands.any { it.command == 0xE1 && it.params.single() == 20 })
        assertTrue(commands.any { it.command == 0xE7 && it.tick == 96 })
        val output = File(directory, "round.mid")
        MusicTrackInterchange.writeMidi(imported.song, output)
        val round = MusicTrackInterchange.readMidi(output)
        assertEquals(notes.map { it.duration }.sorted(), round.channels.flatMap { it.notes }.map { it.duration }.sorted())
        assertTrue(round.channels.flatMap { it.commands }.any { it.command == 0xE1 })
    }
    @Test fun `native automation changes real rendered audio and persists through project and production ROM`() {
        val parser = TestRomHelper.requireRomParser()
        val project = com.supermetroid.editor.data.SmEditProject(romPath = "test.sfc")
        val source = MusicBankResolver.sourceRam(parser, project, 3)
        val original = song()
        fun render(song: NspcSequence.Song): ShortArray {
            val ram = source.copyOf()
            for ((at, data) in NspcSequence.encode(song, 5, conductorAddrOverride = 0x6000)) data.copyInto(ram, at)
            return NativeSpcEmulator().use { engine -> engine.loadFromRam(ram, emptyList(), 5, playbackWarmupSeconds = 0); engine.renderMono(4) }
        }
        val normal = render(original)
        val silent = render(MusicExpression.edit(original, 0, null, NspcSequence.ControlCommand(0, 0xED, intArrayOf(0))))
        assertTrue(normal.any { abs(it.toInt()) > 100 }); assertTrue(silent.all { abs(it.toInt()) < 5 })
        val edited = MusicExpression.edit(original, 0, null, NspcSequence.ControlCommand(48, 0xEE, intArrayOf(24, 0)))
        val faded = render(edited)
        assertTrue((40000 until 60000).sumOf { abs(faded[it].toInt()).toLong() } < (40000 until 60000).sumOf { abs(normal[it].toInt()).toLong() } / 20)
    }
}
