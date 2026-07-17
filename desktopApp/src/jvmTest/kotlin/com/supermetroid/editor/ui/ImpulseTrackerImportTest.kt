package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

class ImpulseTrackerImportTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `it import decodes packed pattern notes into editable song`() {
        val file = File(tempDir, "simple.it")
        file.writeBytes(simpleItModule())

        val result = ImpulseTrackerImport.read(file)
        val song = result.song

        assertEquals("Impulse Tracker", result.report.formatLabel)
        assertEquals((150 / 4.85).roundToInt(), song.tempo)
        assertEquals(1, result.report.noteCount)
        assertEquals(1, result.report.activeChannels)
        assertEquals(1, song.channels[0].notes.size)
        val note = song.channels[0].notes.single()
        assertEquals(0, note.tick)
        assertEquals(12, note.duration)
        assertEquals((36 + 104).coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX), note.noteValue)
        assertEquals(15, note.velocity)
        assertEquals(0x18, note.instrument)
        assertTrue(result.report.warnings.any { it.contains("instruments/samples") })
        assertTrue(result.report.warnings.any { it.contains("tempo command") })
    }

    @Test
    fun `it parser ignores channels above the snes voice limit`() {
        val file = File(tempDir, "wide.it")
        file.writeBytes(simpleItModule(channel = 9))

        val failure = runCatching { ImpulseTrackerImport.read(file) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("no importable note events") == true)
        val parsed = ImpulseTrackerImport.parse(file.readBytes())
        assertTrue(parsed.warnings.any { it.contains("channels above 8") })
    }

    private fun simpleItModule(channel: Int = 1): ByteArray {
        val patternBytes = ByteArrayOutputStream()
        val channelVariable = 0x80 or channel
        patternBytes.write(channelVariable)
        patternBytes.write(0x0F) // note, instrument, volume, command
        patternBytes.write(36)   // IT note
        patternBytes.write(1)    // instrument
        patternBytes.write(64)   // volume
        patternBytes.write(20)   // Txx tempo command
        patternBytes.write(150)
        patternBytes.write(0)    // end row 0
        patternBytes.write(0)    // end row 1
        patternBytes.write(channelVariable)
        patternBytes.write(0x01) // note only
        patternBytes.write(255)  // note off
        patternBytes.write(0)    // end row 2
        repeat(5) { patternBytes.write(0) }

        val patternOffset = 0xC0 + 1 + 4 + 4 + 4
        val out = ByteArrayOutputStream()
        out.write(ByteArray(patternOffset))
        val data = out.toByteArray()
        "IMPM".encodeToByteArray().copyInto(data, 0)
        "Simple IT".encodeToByteArray().copyInto(data, 4)
        writeU16(data, 0x20, 1) // orders
        writeU16(data, 0x22, 1) // instruments
        writeU16(data, 0x24, 1) // samples
        writeU16(data, 0x26, 1) // patterns
        data[0x30] = 64
        data[0x31] = 48
        data[0x32] = 6
        data[0x33] = 125.toByte()
        data[0xC0] = 0
        writeU32(data, 0xC1 + 8, patternOffset)

        val finalOut = ByteArrayOutputStream()
        finalOut.write(data)
        writeU16(finalOut, patternBytes.size())
        writeU16(finalOut, 8)
        writeU32(finalOut, 0)
        finalOut.write(patternBytes.toByteArray())
        return finalOut.toByteArray()
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
