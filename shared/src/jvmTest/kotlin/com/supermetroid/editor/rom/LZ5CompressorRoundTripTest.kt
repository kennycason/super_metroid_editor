package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Pure-data LZ5 round-trip tests — no ROM file required.
 * Compresses synthetic data with LZ5Compressor, then uses the shared strict codec
 * to verify the output matches the input.
 */
class LZ5CompressorRoundTripTest {

    private fun roundTrip(data: ByteArray): ByteArray {
        val compressed = LZ5Compressor.compress(data)
        return LZ5Codec.decompress(compressed).data
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

    @Test
    fun `strict decoder implements all eight engine commands`() {
        val compressed = byteArrayOf(
            0x03, 0x10, 0x20, 0x30, 0x40,             // 0: direct copy
            0x22, 0xAA.toByte(),                       // 1: byte fill
            0x43, 0x11, 0x22,                         // 2: word fill
            0x62, 0xFE.toByte(),                       // 3: incrementing fill
            0x83.toByte(), 0x00, 0x00,                 // 4: absolute copy
            0xA3.toByte(), 0x00, 0x00,                 // 5: inverted absolute copy
            0xC3.toByte(), 0x04,                       // 6: sliding copy
            0xFC.toByte(), 0x03, 0x04,                 // 7: inverted sliding copy
            0xFF.toByte(),
        )
        val expected = byteArrayOf(
            0x10, 0x20, 0x30, 0x40,
            0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(),
            0x11, 0x22, 0x11, 0x22,
            0xFE.toByte(), 0xFF.toByte(), 0x00,
            0x10, 0x20, 0x30, 0x40,
            0xEF.toByte(), 0xDF.toByte(), 0xCF.toByte(), 0xBF.toByte(),
            0xEF.toByte(), 0xDF.toByte(), 0xCF.toByte(), 0xBF.toByte(),
            0x10, 0x20, 0x30, 0x40,
        )

        val result = LZ5Codec.decompress(compressed)
        assertArrayEquals(expected, result.data)
        assertEquals(compressed.size, result.consumed)
    }

    @Test
    fun `strict decoder reports consumed bytes including terminator`() {
        val compressedWithTrailingData = byteArrayOf(0x00, 0x42, 0xFF.toByte(), 0x55)
        val result = LZ5Codec.decompress(compressedWithTrailingData)

        assertArrayEquals(byteArrayOf(0x42), result.data)
        assertEquals(3, result.consumed)
    }

    @Test
    fun `strict decoder rejects truncated and unterminated streams`() {
        listOf(
            byteArrayOf(),
            byteArrayOf(0xE0.toByte()),
            byteArrayOf(0x02, 0x01),
            byteArrayOf(0x20),
            byteArrayOf(0x40, 0x01),
            byteArrayOf(0x60),
            byteArrayOf(0x80.toByte(), 0x00),
            byteArrayOf(0xA0.toByte(), 0x00),
            byteArrayOf(0xC0.toByte()),
            byteArrayOf(0xFC.toByte(), 0x00),
            byteArrayOf(0x00, 0x01),
        ).forEach { malformed ->
            assertFailsWith<LZ5Codec.FormatException>("stream=${malformed.toHex()}") {
                LZ5Codec.decompress(malformed)
            }
        }
    }

    @Test
    fun `strict decoder rejects references to unwritten output`() {
        val invalidAbsoluteCopy = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0xFF.toByte())
        val zeroDistanceSlidingCopy = byteArrayOf(
            0x00, 0x12,
            0xC0.toByte(), 0x00,
            0xFF.toByte(),
        )

        assertFailsWith<LZ5Codec.FormatException> { LZ5Codec.decompress(invalidAbsoluteCopy) }
        assertFailsWith<LZ5Codec.FormatException> { LZ5Codec.decompress(zeroDistanceSlidingCopy) }
    }

    @Test
    fun `engine destination limit is enforced`() {
        val threeByteFill = byteArrayOf(0x22, 0x00, 0xFF.toByte())
        assertFailsWith<LZ5Codec.FormatException> {
            LZ5Codec.decompress(threeByteFill, maxOutputSize = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            LZ5Compressor.compress(ByteArray(LZ5Codec.MAX_ENGINE_OUTPUT + 1))
        }

        val fullBank = ByteArray(LZ5Codec.MAX_ENGINE_OUTPUT)
        assertArrayEquals(fullBank, roundTrip(fullBank))
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
