package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
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
        assertTrue(result.report.warnings.any { it.contains("instrument/sample") })
        assertTrue(result.report.warnings.any { it.contains("tempo command") })
    }

    @Test
    fun `it parser voice packs channels above the snes voice limit`() {
        val file = File(tempDir, "wide.it")
        file.writeBytes(simpleItModule(channel = 9))

        val result = ImpulseTrackerImport.read(file)

        assertEquals(1, result.report.noteCount)
        assertEquals(1, result.report.activeChannels)
        val parsed = ImpulseTrackerImport.parse(file.readBytes())
        assertEquals(1, parsed.patterns.single().channels[8].count { it.note in 0..119 })
    }

    @Test
    fun `it parser reports sample metadata needed for future brr conversion`() {
        val parsed = ImpulseTrackerImport.parse(simpleItModule(withSampleHeader = true))

        assertEquals(1, parsed.samples.size)
        val sample = parsed.samples.single()
        assertEquals("Lead Sample", sample.name)
        assertTrue(sample.associated)
        assertTrue(sample.is16Bit)
        assertTrue(sample.isLooped)
        assertEquals(32, sample.length)
        assertEquals(8, sample.loopStart)
        assertEquals(24, sample.loopEnd)
        assertEquals(8363, sample.c5Speed)
        assertTrue(parsed.warnings.any { it.contains("active sample") })
        assertTrue(parsed.warnings.any { it.contains("16-bit") })
    }

    @Test
    fun `it import builds custom native payload for decoded mono samples`() {
        val file = File(tempDir, "sampled.it")
        file.writeBytes(simpleItModule(withSampleHeader = true))

        val result = ImpulseTrackerImport.read(file, targetPlayIndex = 5)

        assertNotNull(result.nativePayload)
        assertEquals(0, result.song.channels[0].notes.single().instrument)
        assertEquals(42, result.instruments.size)
        assertTrue(result.report.warnings.any { it.contains("Built custom IT native payload") })

        val ram = ByteArray(0x10000)
        SpcData.applyTransferBlocks(ram, result.nativePayload!!.blocks)
        val parsed = NspcSequence.parse(ram, 5)
        assertEquals(1, parsed.channels[0].notes.size)
        val instruments = NspcRenderer.readInstrumentTable(ram)
        assertEquals(0, instruments[0].srcn)
        val sampleStart = readU16(ram, 0x6D00)
        val sampleLoop = readU16(ram, 0x6D02)
        assertEquals(0x6E00, sampleStart)
        assertTrue(sampleLoop >= sampleStart)
        val decoded = SpcData.decodeBrr(ram, sampleStart, maxSamples = 64)
        assertTrue(decoded.any { it.toInt() != 0 })
    }

    @Test
    fun `it import resolves instrument mode keyboard maps to samples`() {
        val file = File(tempDir, "instrument-mode.it")
        file.writeBytes(instrumentMappedItModule())

        val result = ImpulseTrackerImport.read(file, targetPlayIndex = 5)

        assertNotNull(result.nativePayload)
        val note = result.song.channels[0].notes.single()
        assertEquals((48 + 104).coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX), note.noteValue)
        assertEquals(0, note.instrument)
        assertTrue(result.report.warnings.any { it.contains("IT instrument note/sample maps were imported") })
        assertTrue(result.report.warnings.none { it.contains("instrument-mode sample maps/envelopes are not imported yet") })

        val ram = ByteArray(0x10000)
        SpcData.applyTransferBlocks(ram, result.nativePayload!!.blocks)
        val parsed = NspcSequence.parse(ram, 5)
        assertEquals(note.noteValue, parsed.channels[0].notes.single().noteValue)
    }

    @Test
    fun `real world impulse tracker fixture imports when supplied`() {
        val path = System.getProperty("smedit.realItFixture")?.trim().orEmpty()
        assumeTrue(path.isNotEmpty(), "Set -Dsmedit.realItFixture=/path/to/file.it to run this smoke test")
        val file = File(path)
        assumeTrue(file.isFile, "Real IT fixture does not exist: $path")

        val result = ImpulseTrackerImport.read(file)

        assertEquals("Impulse Tracker", result.report.formatLabel)
        assertTrue(result.report.noteCount > 0)
        assertTrue(result.report.activeChannels in 1..8)
    }

    @Test
    fun `real world impulse tracker native payload fixture imports when supplied`() {
        val path = System.getProperty("smedit.realItNativeFixture")?.trim().orEmpty()
        assumeTrue(path.isNotEmpty(), "Set -Dsmedit.realItNativeFixture=/path/to/file.it to run this smoke test")
        val file = File(path)
        assumeTrue(file.isFile, "Real IT native fixture does not exist: $path")

        val result = ImpulseTrackerImport.read(file, targetPlayIndex = 5)

        assertNotNull(result.nativePayload, result.report.warnings.joinToString("\n"))
        assertTrue(result.report.noteCount > 0)
        assertTrue(
            result.song.channels.flatMap { it.notes }.map { it.noteValue }.distinct().size > 4,
            "Expected real fixture import to preserve more than repeated C/C# pulses"
        )
        assertTrue(result.report.warnings.any { it.contains("Built custom IT native payload") })
    }

    private fun simpleItModule(
        channel: Int = 1,
        withSampleHeader: Boolean = false
    ): ByteArray {
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

        val sampleHeaderOffset = 0xC0 + 1 + 4 + 4 + 4
        val patternOffset = sampleHeaderOffset + if (withSampleHeader) 0x50 else 0
        val sampleDataOffset = patternOffset + 8 + patternBytes.size()
        val out = ByteArrayOutputStream()
        out.write(ByteArray(patternOffset))
        val data = out.toByteArray()
        "IMPM".encodeToByteArray().copyInto(data, 0)
        "Simple IT".encodeToByteArray().copyInto(data, 4)
        writeU16(data, 0x20, 1) // orders
        writeU16(data, 0x22, 1) // instruments
        writeU16(data, 0x24, 1) // samples
        writeU16(data, 0x26, 1) // patterns
        data[0x30] = 128.toByte()
        data[0x31] = 48
        data[0x32] = 6
        data[0x33] = 125.toByte()
        repeat(64) { data[0x80 + it] = 64 }
        data[0xC0] = 0
        if (withSampleHeader) {
            writeU32(data, 0xC1 + 4, sampleHeaderOffset)
            writeSampleHeader(data, sampleHeaderOffset, sampleDataOffset)
        }
        writeU32(data, 0xC1 + 8, patternOffset)

        val finalOut = ByteArrayOutputStream()
        finalOut.write(data)
        writeU16(finalOut, patternBytes.size())
        writeU16(finalOut, 8)
        writeU32(finalOut, 0)
        finalOut.write(patternBytes.toByteArray())
        if (withSampleHeader) {
            finalOut.write(testSamplePcm16())
        }
        return finalOut.toByteArray()
    }

    private fun instrumentMappedItModule(): ByteArray {
        val patternBytes = ByteArrayOutputStream()
        val channelVariable = 0x80 or 1
        patternBytes.write(channelVariable)
        patternBytes.write(0x07) // note, instrument, volume
        patternBytes.write(36)   // IT note before instrument keyboard mapping
        patternBytes.write(1)    // instrument
        patternBytes.write(64)   // volume
        patternBytes.write(0)    // end row 0
        patternBytes.write(0)    // end row 1
        patternBytes.write(channelVariable)
        patternBytes.write(0x01) // note only
        patternBytes.write(255)  // note off
        patternBytes.write(0)    // end row 2
        repeat(5) { patternBytes.write(0) }

        val instrumentHeaderOffset = 0xC0 + 1 + 4 + 4 + 4
        val sampleHeaderOffset = instrumentHeaderOffset + 0x230
        val patternOffset = sampleHeaderOffset + 0x50
        val sampleDataOffset = patternOffset + 8 + patternBytes.size()
        val out = ByteArrayOutputStream()
        out.write(ByteArray(patternOffset))
        val data = out.toByteArray()
        "IMPM".encodeToByteArray().copyInto(data, 0)
        "Instrument IT".encodeToByteArray().copyInto(data, 4)
        writeU16(data, 0x20, 1) // orders
        writeU16(data, 0x22, 1) // instruments
        writeU16(data, 0x24, 1) // samples
        writeU16(data, 0x26, 1) // patterns
        writeU16(data, 0x2C, 0x04) // use instruments
        data[0x30] = 128.toByte()
        data[0x31] = 48
        data[0x32] = 6
        data[0x33] = 125.toByte()
        repeat(64) { data[0x80 + it] = 64 }
        data[0xC0] = 0
        writeU32(data, 0xC1, instrumentHeaderOffset)
        writeU32(data, 0xC1 + 4, sampleHeaderOffset)
        writeU32(data, 0xC1 + 8, patternOffset)
        writeInstrumentHeader(data, instrumentHeaderOffset, mappedInputNote = 36, mappedOutputNote = 48, sampleIndex = 1)
        writeSampleHeader(data, sampleHeaderOffset, sampleDataOffset)

        val finalOut = ByteArrayOutputStream()
        finalOut.write(data)
        writeU16(finalOut, patternBytes.size())
        writeU16(finalOut, 8)
        writeU32(finalOut, 0)
        finalOut.write(patternBytes.toByteArray())
        finalOut.write(testSamplePcm16())
        return finalOut.toByteArray()
    }

    private fun writeInstrumentHeader(
        data: ByteArray,
        offset: Int,
        mappedInputNote: Int,
        mappedOutputNote: Int,
        sampleIndex: Int
    ) {
        "IMPI".encodeToByteArray().copyInto(data, offset)
        "Mapped Instrument".encodeToByteArray().copyInto(data, offset + 0x20)
        for (note in 0 until 120) {
            data[offset + 0x40 + note * 2] = note.toByte()
            data[offset + 0x40 + note * 2 + 1] = sampleIndex.toByte()
        }
        data[offset + 0x40 + mappedInputNote * 2] = mappedOutputNote.toByte()
    }

    private fun writeSampleHeader(data: ByteArray, offset: Int, sampleDataOffset: Int) {
        "IMPS".encodeToByteArray().copyInto(data, offset)
        "Lead Sample".encodeToByteArray().copyInto(data, offset + 0x14)
        data[offset + 0x11] = 64
        data[offset + 0x12] = 0x13 // associated, 16-bit, looped
        data[offset + 0x13] = 64
        data[offset + 0x2E] = 0x01 // signed PCM
        writeU32(data, offset + 0x30, 32)
        writeU32(data, offset + 0x34, 8)
        writeU32(data, offset + 0x38, 24)
        writeU32(data, offset + 0x3C, 8363)
        writeU32(data, offset + 0x48, sampleDataOffset)
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

    private fun testSamplePcm16(): ByteArray {
        val out = ByteArray(64)
        for (i in 0 until 32) {
            val phase = if (i < 16) i else 31 - i
            val value = ((phase - 8) * 1800).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
