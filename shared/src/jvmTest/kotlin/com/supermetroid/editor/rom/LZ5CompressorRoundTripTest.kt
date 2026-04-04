package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-data LZ5 round-trip tests — no ROM file required.
 * Compresses synthetic data with LZ5Compressor, then decompresses
 * with RomParser and verifies the output matches the input.
 */
class LZ5CompressorRoundTripTest {

    private fun roundTrip(data: ByteArray): ByteArray {
        val compressed = LZ5Compressor.compress(data)
        // Wrap in a buffer large enough for RomParser to parse
        val buf = ByteArray(compressed.size + 0x200)
        System.arraycopy(compressed, 0, buf, 0, compressed.size)
        return RomParser(buf).decompressLZ5AtPc(0)
    }

    @Test
    fun `round-trip empty data`() {
        val data = ByteArray(0)
        // Empty data compresses to just 0xFF terminator
        val compressed = LZ5Compressor.compress(data)
        assertTrue(compressed.isNotEmpty())
        assertTrue(compressed.last() == 0xFF.toByte())
    }

    @Test
    fun `round-trip single byte`() {
        val data = byteArrayOf(0x42)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip all zeros (byte fill)`() {
        val data = ByteArray(256) // triggers byte fill compression
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip alternating bytes (word fill)`() {
        val data = ByteArray(200) { if (it % 2 == 0) 0xAA.toByte() else 0x55.toByte() }
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip increasing sequence`() {
        val data = ByteArray(128) { it.toByte() }
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip repeated pattern (dictionary match)`() {
        val pattern = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val data = ByteArray(pattern.size * 20)
        for (i in data.indices) data[i] = pattern[i % pattern.size]
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip random data`() {
        val rng = java.util.Random(42)
        val data = ByteArray(500)
        rng.nextBytes(data)
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip mixed patterns`() {
        // Combine: raw, byte fill, word fill, repeated pattern
        val parts = mutableListOf<Byte>()
        // 20 bytes of random
        val rng = java.util.Random(123)
        repeat(20) { parts.add(rng.nextInt(256).toByte()) }
        // 100 bytes of 0xFF fill
        repeat(100) { parts.add(0xFF.toByte()) }
        // 50 bytes alternating 0xAB/0xCD
        repeat(50) { parts.add(if (it % 2 == 0) 0xAB.toByte() else 0xCD.toByte()) }
        // 80 bytes of repeated 8-byte pattern
        val pat = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        repeat(80) { parts.add(pat[it % pat.size]) }
        // 30 bytes increasing
        repeat(30) { parts.add(it.toByte()) }

        val data = parts.toByteArray()
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `round-trip large data`() {
        // 8KB of semi-structured data simulating level data
        val data = ByteArray(8192)
        val rng = java.util.Random(7)
        for (i in data.indices) {
            data[i] = when {
                i % 64 < 4 -> 0x00   // lots of zeros (like empty tiles)
                i % 128 < 8 -> 0x80.toByte()  // solid block headers
                else -> rng.nextInt(256).toByte()
            }
        }
        assertArrayEquals(data, roundTrip(data))
    }

    @Test
    fun `compressed output is smaller than raw for repetitive data`() {
        val data = ByteArray(1024) // all zeros
        val compressed = LZ5Compressor.compress(data)
        assertTrue(compressed.size < data.size,
            "Compressed (${compressed.size}) should be smaller than raw (${data.size})")
    }

    @Test
    fun `compressed output ends with FF terminator`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val compressed = LZ5Compressor.compress(data)
        assertTrue(compressed.last() == 0xFF.toByte(), "Last byte should be 0xFF terminator")
    }
}
