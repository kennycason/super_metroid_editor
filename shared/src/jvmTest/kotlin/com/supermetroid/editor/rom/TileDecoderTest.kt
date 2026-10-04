package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileDecoderTest {
    private val decoder = TileDecoder()

    @Test
    fun `standard 4bpp decoder preserves bitplane significance and row stride`() {
        val expected = IntArray(64) { index -> ((index % 8) + (index / 8) * 3) and 0x0F }
        val encoded = encodeStandard4bpp(expected)

        assertContentEquals(expected, decoder.decode4bppTileIndices(encoded))
        val matrix = decoder.decodeTile(encoded)
        assertEquals(expected[7 * 8 + 6], matrix[7][6])
    }

    @Test
    fun `global split-plane decoder joins odd and even halves`() {
        val first = IntArray(64) { index -> (index + 1) and 0x0F }
        val second = IntArray(64) { index -> (15 - index) and 0x0F }
        val encoded = encodeGlobalSplitPlane4bpp(listOf(first, second))

        assertContentEquals(first, decoder.decodeSplitPlane4bppTileIndices(encoded, 0, 2))
        assertContentEquals(second, decoder.decodeSplitPlane4bppTileIndices(encoded, 1, 2))
    }

    @Test
    fun `standard 2bpp decoder returns all four colour indices`() {
        val expected = IntArray(64) { index -> (index + index / 8) and 3 }
        val encoded = encodeStandard2bpp(expected)

        assertContentEquals(expected, decoder.decode2bppTileIndices(encoded))
        assertEquals(setOf(0, 1, 2, 3), decoder.decode2bppTileIndices(encoded).toSet())
    }

    @Test
    fun `tile decoder rejects incomplete or invalid ranges`() {
        assertFailsWith<IllegalArgumentException> { decoder.decode4bppTileIndices(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { decoder.decode2bppTileIndices(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> {
            decoder.decodeSplitPlane4bppTileIndices(ByteArray(64), tileIndex = 2, tileCount = 2)
        }
    }

    private fun encodeStandard4bpp(pixels: IntArray): ByteArray {
        val result = ByteArray(32)
        for (row in 0 until 8) {
            val planes = encodeRow(pixels, row)
            result[row * 2] = planes[0]
            result[row * 2 + 1] = planes[1]
            result[row * 2 + 16] = planes[2]
            result[row * 2 + 17] = planes[3]
        }
        return result
    }

    private fun encodeGlobalSplitPlane4bpp(tiles: List<IntArray>): ByteArray {
        val result = ByteArray(tiles.size * 32)
        val half = result.size / 2
        tiles.forEachIndexed { tileIndex, pixels ->
            val lowOffset = tileIndex * 16
            val highOffset = half + tileIndex * 16
            for (row in 0 until 8) {
                val planes = encodeRow(pixels, row)
                result[lowOffset + row] = planes[0]
                result[highOffset + row] = planes[1]
                result[lowOffset + 8 + row] = planes[2]
                result[highOffset + 8 + row] = planes[3]
            }
        }
        return result
    }

    private fun encodeStandard2bpp(pixels: IntArray): ByteArray {
        val result = ByteArray(16)
        for (row in 0 until 8) {
            val planes = encodeRow(pixels, row)
            result[row * 2] = planes[0]
            result[row * 2 + 1] = planes[1]
        }
        return result
    }

    private fun encodeRow(pixels: IntArray, row: Int): ByteArray {
        val planes = IntArray(4)
        for (column in 0 until 8) {
            val value = pixels[row * 8 + column]
            val bit = 7 - column
            for (plane in 0 until 4) {
                if (value and (1 shl plane) != 0) planes[plane] = planes[plane] or (1 shl bit)
            }
        }
        return ByteArray(4) { planes[it].toByte() }
    }
}
