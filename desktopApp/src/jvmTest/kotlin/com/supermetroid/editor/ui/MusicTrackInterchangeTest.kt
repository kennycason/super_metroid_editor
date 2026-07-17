package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.TestRomHelper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

class MusicTrackInterchangeTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `midi export imports back into editable song notes`() {
        val song = NspcSequence.Song(tempo = 48, title = "Round Trip")
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 48,
            noteValue = NspcSequence.NOTE_MIN + 24,
            velocity = 12,
            quantize = 6,
            instrument = 5
        )
        song.channels[3].notes += NspcSequence.Note(
            tick = 96,
            duration = 72,
            noteValue = NspcSequence.NOTE_MIN + 31,
            velocity = 8,
            quantize = 4,
            instrument = 7
        )

        val file = File(tempDir, "roundtrip.mid")
        MusicTrackInterchange.writeMidi(song, file)
        val result = MusicTrackInterchange.readMidiWithReport(file)
        val imported = result.song

        assertEquals(48, imported.tempo)
        assertEquals("MIDI", result.report.formatLabel)
        assertEquals(2, result.report.noteCount)
        assertEquals(2, result.report.activeChannels)
        assertTrue(result.report.warnings.isEmpty())
        assertEquals(1, imported.channels[0].notes.size)
        assertEquals(1, imported.channels[3].notes.size)
        assertEquals(0, imported.channels[0].notes.single().tick)
        assertEquals(48, imported.channels[0].notes.single().duration)
        assertEquals(NspcSequence.NOTE_MIN + 24, imported.channels[0].notes.single().noteValue)
        assertEquals(5, imported.channels[0].notes.single().instrument)
        assertEquals(96, imported.channels[3].notes.single().tick)
        assertEquals(72, imported.channels[3].notes.single().duration)
    }

    @Test
    fun `midi export uses general midi programs but reimport restores smedit instruments`() {
        val song = NspcSequence.Song(tempo = 40, title = "Program Map")
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 96,
            noteValue = NspcSequence.NOTE_MIN + 36,
            velocity = 12,
            quantize = 7,
            instrument = 0x18
        )
        song.channels[0].notes += NspcSequence.Note(
            tick = 96,
            duration = 96,
            noteValue = NspcSequence.NOTE_MIN + 40,
            velocity = 12,
            quantize = 7,
            instrument = 0x1B
        )
        val instruments = MutableList(0x1C) { i ->
            NspcRenderer.InstrumentEntry(
                index = i,
                tableAddr = 0x6C00 + i * 6,
                srcn = i,
                adsr1 = 0xFF,
                adsr2 = 0xE0,
                gain = 0x7F,
                pitchAdj = 0x1000
            )
        }
        instruments[0x18] = instruments[0x18].copy(srcn = 24)
        instruments[0x1B] = instruments[0x1B].copy(srcn = 27)

        val file = File(tempDir, "program-map.mid")
        MusicTrackInterchange.writeMidi(song, file, instruments)
        val programChanges = midiProgramChanges(file)
        val imported = MusicTrackInterchange.readMidi(file)

        assertTrue(programChanges.isNotEmpty())
        assertTrue(programChanges.none { it == 0x18 || it == 0x1B }, "MIDI programs should be GM approximations, not raw SM instrument ids")
        assertEquals(0x18, imported.channels[0].notes[0].instrument)
        assertEquals(0x1B, imported.channels[0].notes[1].instrument)
    }

    @Test
    fun `midi import report warns when data is folded clamped or ignored`() {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        track.add(MidiEvent(short(ShortMessage.PROGRAM_CHANGE, 9, 12, 0), 0))
        track.add(MidiEvent(short(ShortMessage.CONTROL_CHANGE, 0, 7, 100), 0))
        track.add(MidiEvent(meta(0x01, "ignored metadata"), 0))
        track.add(MidiEvent(short(ShortMessage.NOTE_ON, 9, 120, 80), 0))
        track.add(MidiEvent(short(ShortMessage.NOTE_OFF, 9, 120, 0), 480))

        val file = File(tempDir, "lossy.mid")
        MidiSystem.write(sequence, 1, file)
        val result = MusicTrackInterchange.readMidiWithReport(file)

        assertEquals(1, result.song.channels[1].notes.size)
        assertEquals(96, result.song.channels[1].notes.single().duration)
        assertEquals(NspcSequence.NOTE_MAX, result.song.channels[1].notes.single().noteValue)
        assertTrue(result.report.hasWarnings)
        assertTrue(result.report.warnings.any { it.contains("folded into the 8 SNES voices") })
        assertTrue(result.report.warnings.any { it.contains("clamped") })
        assertTrue(result.report.warnings.any { it.contains("unsupported MIDI event") })
        assertTrue(result.report.warnings.any { it.contains("unsupported MIDI metadata") })
    }

    @Test
    fun `lower norfair midi export uses bounded parsed song duration`() {
        val parser = TestRomHelper.loadRomParser() ?: return
        val ram = SpcData.buildInitialSpcRam(parser)
        val blocks = SpcData.findSongSetTransferData(parser, 0x18)
        SpcData.applyTransferBlocks(ram, blocks)
        val song = NspcSequence.parse(ram, 5)

        val estimatedSeconds = song.totalTicks * 512.0 / song.tempo.coerceAtLeast(1) / 1000.0
        assertTrue(estimatedSeconds < 300.0, "Lower Norfair parsed duration should stay under 5 minutes")

        val file = File(tempDir, "lower_norfair.mid")
        MusicTrackInterchange.writeMidi(song, file)
        val sequence = MidiSystem.getSequence(file)
        val midiSeconds = sequence.microsecondLength / 1_000_000.0

        assertTrue(midiSeconds < 300.0, "Lower Norfair MIDI export should not be stretched to 15 minutes; got $midiSeconds")
    }

    @Test
    fun `nspc track bundle preserves notes commands and instruments`() {
        val song = NspcSequence.Song(tempo = 33, title = "Bundle")
        song.channels[1].notes += NspcSequence.Note(
            tick = 12,
            duration = 36,
            noteValue = NspcSequence.NOTE_MIN + 18,
            velocity = 9,
            quantize = 5,
            instrument = 3
        )
        song.channels[1].commands += NspcSequence.ControlCommand(
            tick = 0,
            command = 0xED,
            params = intArrayOf(0x70)
        )
        val instruments = listOf(
            NspcRenderer.InstrumentEntry(
                index = 3,
                tableAddr = 0x6C12,
                srcn = 4,
                adsr1 = 0x8F,
                adsr2 = 0xE0,
                gain = 0x7F,
                pitchAdj = 0x1234
            )
        )

        val file = File(tempDir, "bundle.nspc")
        MusicTrackInterchange.writeNspcTrack(song, instruments, songSet = 0x0F, playIndex = 5, file = file)
        val imported = MusicTrackInterchange.readNative(file, selectedPlayIndex = 5)

        assertEquals("N-SPC", imported.formatLabel)
        assertEquals(5, imported.sourcePlayIndex)
        assertEquals("N-SPC", imported.report.formatLabel)
        assertEquals(1, imported.report.noteCount)
        assertTrue(imported.report.warnings.isEmpty())
        assertEquals(33, imported.song.tempo)
        assertEquals(NspcSequence.NOTE_MIN + 18, imported.song.channels[1].notes.single().noteValue)
        assertEquals(0xED, imported.song.channels[1].commands.single().command)
        assertArrayEquals(intArrayOf(0x70), imported.song.channels[1].commands.single().params)
        assertEquals(0x1234, imported.instruments.single().pitchAdj)
    }

    @Test
    fun `spc snapshot import parses compatible nspc sequence RAM`() {
        val playIndex = 5
        val song = NspcSequence.Song(tempo = 40, title = "SPC Fixture")
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 48,
            noteValue = NspcSequence.NOTE_MIN + 12,
            velocity = 15,
            quantize = 7,
            instrument = 0
        )
        val ram = ByteArray(0x10000)
        val writes = NspcSequence.encode(
            song = song,
            playIndex = playIndex,
            spcRam = ram,
            failOnOverflow = true,
            conductorAddrOverride = 0x5820,
            sequenceEndAddr = 0x6000
        )
        for ((addr, data) in writes) data.copyInto(ram, addr)

        val file = File(tempDir, "snapshot.spc")
        file.writeBytes(NativeSpcEmulator.buildSpcFileImage(ram, "SPC Fixture"))
        val imported = MusicTrackInterchange.readNative(file, selectedPlayIndex = playIndex)

        assertEquals("SPC", imported.formatLabel)
        assertEquals(playIndex, imported.sourcePlayIndex)
        assertEquals("SPC", imported.report.formatLabel)
        assertTrue(imported.report.warnings.any { it.contains("compatible N-SPC sequence data") })
        assertEquals(40, imported.song.tempo)
        assertEquals(1, imported.song.channels[0].notes.size)
        assertEquals(NspcSequence.NOTE_MIN + 12, imported.song.channels[0].notes.single().noteValue)
        assertTrue(imported.instruments.isNotEmpty())
    }

    @Test
    fun `raw nspc transfer import parses mITroid-style native payload`() {
        val sourcePlayIndex = 5
        val selectedPlayIndex = 6
        val song = NspcSequence.Song(tempo = 44, title = "Raw Fixture")
        song.channels[0].notes += NspcSequence.Note(
            tick = 0,
            duration = 48,
            noteValue = NspcSequence.NOTE_MIN + 19,
            velocity = 13,
            quantize = 7,
            instrument = 1
        )
        val writes = NspcSequence.encode(
            song = song,
            playIndex = sourcePlayIndex,
            spcRam = ByteArray(0x10000),
            failOnOverflow = true,
            conductorAddrOverride = 0x5830,
            sequenceEndAddr = 0x6000
        )

        val file = File(tempDir, "musicdata.nspc")
        file.writeBytes(rawTransferChain(writes, includeMitroidEntry = true))
        val imported = MusicTrackInterchange.readNative(file, selectedPlayIndex = selectedPlayIndex)

        assertEquals("Raw N-SPC", imported.formatLabel)
        assertEquals(sourcePlayIndex, imported.sourcePlayIndex)
        assertEquals(sourcePlayIndex, imported.nativePayload?.sourcePlayIndex)
        assertEquals("musicdata.nspc", imported.nativePayload?.sourceFileName)
        assertTrue(imported.nativePayload?.blocks?.isNotEmpty() == true)
        assertTrue(imported.report.warnings.any { it.contains("native payload") })
        assertEquals(44, imported.song.tempo)
        assertEquals(1, imported.song.channels[0].notes.size)
        assertEquals(NspcSequence.NOTE_MIN + 19, imported.song.channels[0].notes.single().noteValue)
    }

    private fun short(command: Int, channel: Int, data1: Int, data2: Int): ShortMessage =
        ShortMessage().also { it.setMessage(command, channel, data1, data2) }

    private fun meta(type: Int, text: String): MetaMessage {
        val data = text.encodeToByteArray()
        return MetaMessage().also { it.setMessage(type, data, data.size) }
    }

    private fun midiProgramChanges(file: File): List<Int> {
        val sequence = MidiSystem.getSequence(file)
        return sequence.tracks.flatMap { track ->
            (0 until track.size()).mapNotNull { index ->
                val message = track.get(index).message as? ShortMessage
                message?.takeIf { it.command == ShortMessage.PROGRAM_CHANGE }?.data1
            }
        }
    }

    private fun rawTransferChain(writes: Map<Int, ByteArray>, includeMitroidEntry: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        for ((addr, data) in writes.toSortedMap()) {
            writeU16(out, data.size)
            writeU16(out, addr)
            out.write(data)
        }
        writeU16(out, 0)
        if (includeMitroidEntry) {
            writeU16(out, 0x1500)
        }
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}
