package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NspcRendererTest {

    @Test
    fun `readInstrumentTable exposes sample and envelope metadata`() {
        val ram = ByteArray(65536)
        val instrumentTable = 0x6C00
        val sampleDirectory = 0x6D00
        val srcn = 3
        val sampleStart = 0x2000
        val sampleLoop = 0x2009

        ram[instrumentTable] = srcn.toByte()
        ram[instrumentTable + 1] = 0x8F.toByte()
        ram[instrumentTable + 2] = 0xE4.toByte()
        ram[instrumentTable + 3] = 0x7F
        ram[instrumentTable + 4] = 0x34
        ram[instrumentTable + 5] = 0x12
        ram[instrumentTable + 6] = 40

        val dir = sampleDirectory + srcn * 4
        ram[dir] = (sampleStart and 0xFF).toByte()
        ram[dir + 1] = ((sampleStart shr 8) and 0xFF).toByte()
        ram[dir + 2] = (sampleLoop and 0xFF).toByte()
        ram[dir + 3] = ((sampleLoop shr 8) and 0xFF).toByte()

        ram[sampleStart] = 0x00
        ram[sampleStart + 9] = 0x03

        val entry = NspcRenderer.readInstrumentTable(ram).single()

        assertEquals(srcn, entry.srcn)
        assertEquals(0, entry.index)
        assertEquals(instrumentTable, entry.tableAddr)
        assertEquals(0x8F, entry.adsr1)
        assertEquals(0xE4, entry.adsr2)
        assertEquals(0x7F, entry.gain)
        assertEquals(0x1234, entry.pitchAdj)
        assertEquals(sampleStart, entry.sampleStartAddr)
        assertEquals(sampleLoop, entry.sampleLoopAddr)
        assertEquals(18, entry.brrByteLength)
        assertTrue(entry.brrLoops)
    }

    @Test
    fun `readInstrumentTable does not invent sample pointers for non sample srcn`() {
        val ram = ByteArray(65536)
        val instrumentTable = 0x6C00

        ram[instrumentTable] = 0x80.toByte()
        ram[instrumentTable + 6] = 40

        val entry = NspcRenderer.readInstrumentTable(ram).single()

        assertEquals(0x80, entry.srcn)
        assertEquals(-1, entry.sampleStartAddr)
        assertEquals(-1, entry.sampleLoopAddr)
        assertEquals(-1, entry.brrByteLength)
        assertFalse(entry.brrLoops)
    }

    @Test
    fun `renderToWav can preserve raw overlay amplitude without normalization`() {
        val ram = ByteArray(65536)
        val samplePcm = ShortArray(32) { i -> if (i % 2 == 0) 4000 else -4000 }
        installTestInstrument(ram, samplePcm = samplePcm, pitchAdj = 0x1000)

        val events = listOf(
            NspcRenderer.NoteEvent(
                tickStart = 0,
                tickDuration = 48,
                channel = 0,
                noteValue = 0x98,
                instrumentIdx = 0,
                volume = 1.0f
            )
        )

        val normalized = NspcRenderer.renderToWav(events, ram, tempo = 27, maxSeconds = 1.0)
        val raw = NspcRenderer.renderToWav(events, ram, tempo = 27, maxSeconds = 1.0, normalize = false)

        assertTrue(peak(raw) > 0)
        assertTrue(peak(normalized) > peak(raw))
        assertTrue(peak(normalized) >= 27_000)
    }

    @Test
    fun `instrument pitch word is a multiplier not an additive offset`() {
        val unitRam = ByteArray(65536)
        val tunedRam = ByteArray(65536)
        val samplePcm = ShortArray(512) { i ->
            val phase = i % 64
            ((if (phase < 32) phase else 64 - phase) * 180).toShort()
        }
        installTestInstrument(unitRam, samplePcm = samplePcm, pitchAdj = 0x1000)
        installTestInstrument(tunedRam, samplePcm = samplePcm, pitchAdj = 0x2003)

        val event = listOf(
            NspcRenderer.NoteEvent(
                tickStart = 0,
                tickDuration = 96,
                channel = 0,
                noteValue = 0x89,
                instrumentIdx = 0,
                volume = 1.0f
            )
        )

        val unit = NspcRenderer.renderToWav(event, unitRam, tempo = 27, maxSeconds = 1.0, normalize = false)
        val tuned = NspcRenderer.renderToWav(event, tunedRam, tempo = 27, maxSeconds = 1.0, normalize = false)

        assertTrue(lastAudibleIndex(unit) > lastAudibleIndex(tuned))
        assertTrue(
            lastAudibleIndex(tuned) > lastAudibleIndex(unit) / 4,
            "0x2003 should be near 2x tuning, not an additive/clamped 16x jump"
        )
    }

    private fun installTestInstrument(
        ram: ByteArray,
        samplePcm: ShortArray,
        pitchAdj: Int,
        sampleStart: Int = 0x2000
    ) {
        val brr = SpcData.encodeBrr(samplePcm)
        ram[0x6C00] = 0x00 // instrument 0 -> sample directory entry 0
        ram[0x6C01] = 0x8F.toByte()
        ram[0x6C02] = 0xE4.toByte()
        ram[0x6C03] = 0x7F
        ram[0x6C04] = (pitchAdj and 0xFF).toByte()
        ram[0x6C05] = ((pitchAdj shr 8) and 0xFF).toByte()
        ram[0x6C06] = 40
        ram[0x6D00] = (sampleStart and 0xFF).toByte()
        ram[0x6D01] = ((sampleStart shr 8) and 0xFF).toByte()
        val noLoopPtr = sampleStart + brr.size
        ram[0x6D02] = (noLoopPtr and 0xFF).toByte()
        ram[0x6D03] = ((noLoopPtr shr 8) and 0xFF).toByte()
        brr.copyInto(ram, sampleStart)
    }

    private fun lastAudibleIndex(samples: ShortArray): Int {
        val threshold = 8
        for (i in samples.indices.reversed()) {
            if (kotlin.math.abs(samples[i].toInt()) > threshold) return i
        }
        return -1
    }

    private fun peak(samples: ShortArray): Int =
        samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
}
