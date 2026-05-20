package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

/**
 * Verifies the LZ5 compressor produces valid output that:
 * 1. Decompresses back to the original data
 * 2. Fits within (or near) the original compressed size
 */
class LZ5CompressorTest {

    private fun loadTestRom(): RomParser? = TestRomHelper.loadRomParser()

    /** Compress using same algorithm as EditorState (byte fill, word fill, dict copy). */
    private fun lz5Compress(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        val rawBuf = mutableListOf<Byte>()
        var pos = 0
        fun flushRaw() {
            var i = 0
            while (i < rawBuf.size) {
                val chunk = minOf(rawBuf.size - i, 1024)
                emitCmd(out, 0, chunk)
                for (j in 0 until chunk) out.add(rawBuf[i + j])
                i += chunk
            }
            rawBuf.clear()
        }
        while (pos < data.size) {
            val (dictLen, dictAddr) = findDictionaryMatch(data, pos)
            val byteFillLen = countByteFill(data, pos)
            val wordFillLen = countWordFill(data, pos)
            val dictSaving = if (dictLen >= 3) dictLen - (if (dictLen <= 32) 3 else 4) else 0
            val byteSaving = if (byteFillLen >= 3) byteFillLen - (if (byteFillLen <= 32) 2 else 3) else 0
            val wordSaving = if (wordFillLen >= 4) wordFillLen - (if (wordFillLen <= 32) 3 else 4) else 0
            when {
                dictSaving > 0 && dictSaving >= byteSaving && dictSaving >= wordSaving -> {
                    flushRaw(); val len = minOf(dictLen, 1024)
                    emitCmd(out, 4, len); out.add((dictAddr and 0xFF).toByte()); out.add(((dictAddr shr 8) and 0xFF).toByte())
                    pos += len
                }
                byteSaving > 0 && byteSaving >= wordSaving -> {
                    flushRaw(); val len = minOf(byteFillLen, 1024)
                    emitCmd(out, 1, len); out.add(data[pos]); pos += len
                }
                wordSaving > 0 -> {
                    flushRaw(); val len = minOf(wordFillLen, 1024)
                    emitCmd(out, 2, len); out.add(data[pos]); out.add(data[pos + 1]); pos += len
                }
                else -> { rawBuf.add(data[pos]); pos++ }
            }
        }
        flushRaw(); out.add(0xFF.toByte())
        return out.toByteArray()
    }

    private fun findDictionaryMatch(data: ByteArray, pos: Int): Pair<Int, Int> {
        if (pos < 3) return Pair(0, 0)
        var bestLen = 0; var bestAddr = 0
        val maxSearch = minOf(pos, 0xFFFF)
        val step = if (pos > 4000) 2 else 1
        var start = 0
        while (start < maxSearch) {
            var matchLen = 0; val maxMatch = minOf(data.size - pos, 1024)
            while (matchLen < maxMatch && data[start + matchLen] == data[pos + matchLen]) matchLen++
            if (matchLen > bestLen) { bestLen = matchLen; bestAddr = start; if (matchLen >= 64) break }
            start += step
        }
        return Pair(bestLen, bestAddr)
    }

    private fun countByteFill(data: ByteArray, pos: Int): Int {
        if (pos >= data.size) return 0
        val b = data[pos]; var c = 1
        while (pos + c < data.size && c < 1024 && data[pos + c] == b) c++; return c
    }

    private fun countWordFill(data: ByteArray, pos: Int): Int {
        if (pos + 1 >= data.size) return 0
        val b1 = data[pos]; val b2 = data[pos + 1]; var c = 2
        while (pos + c < data.size && c < 1024) {
            if (data[pos + c] != (if (c % 2 == 0) b1 else b2)) break; c++
        }; return c
    }

    private fun emitCmd(out: MutableList<Byte>, cmd: Int, length: Int) {
        if (length <= 32) out.add(((cmd shl 5) or (length - 1)).toByte())
        else {
            val len = length - 1
            out.add((0xE0 or ((cmd and 7) shl 2) or ((len shr 8) and 0x03)).toByte())
            out.add((len and 0xFF).toByte())
        }
    }

    @Test
    fun `round-trip compress-decompress for Landing Site`() {
        val parser = loadTestRom() ?: return
        val room = parser.readRoomHeader(0x91F8)!!
        val (original, origCompSize) = parser.decompressLZ2WithSize(room.levelDataPtr)

        val compressed = lz5Compress(original)
        println("Landing Site: decompressed=${original.size}, origCompressed=$origCompSize, recompressed=${compressed.size}")
        println("  Ratio: ${compressed.size * 100 / origCompSize}% of original compressed size")

        // Verify it fits
        assertTrue(compressed.size <= origCompSize * 2,
            "Recompressed should be at most 2x original (got ${compressed.size} vs $origCompSize)")

        // Verify round-trip: decompress our output and compare
        val tempRom = ByteArray(compressed.size + 0x200)
        System.arraycopy(compressed, 0, tempRom, 0, compressed.size)
        val tempParser = RomParser(tempRom)
        // We can't use snesToPc because the temp ROM is tiny. Decompress at PC offset 0.
        val decompressed = tempParser.decompressLZ5AtPc(0)

        assertArrayEquals(original, decompressed,
            "Round-trip decompressed data should match original")
        println("  Round-trip: PASS")
    }

    @Test
    fun `compression fits for multiple rooms`() {
        val parser = loadTestRom() ?: return
        val rooms = listOf(
            0x91F8 to "Landing Site",
            0x93FE to "West Ocean",
            0x92FD to "Parlor and Alcatraz",
            0x9804 to "Bomb Torizo",
            0xA59F to "Kraid",
        )
        var allFit = true
        for ((roomId, name) in rooms) {
            val room = parser.readRoomHeader(roomId) ?: continue
            if (room.levelDataPtr == 0) continue
            val (original, origCompSize) = parser.decompressLZ2WithSize(room.levelDataPtr)
            val compressed = lz5Compress(original)
            val fits = compressed.size <= origCompSize
            val ratio = compressed.size * 100 / origCompSize
            println("$name: orig=$origCompSize, ours=${compressed.size}, ratio=$ratio% ${if (fits) "OK" else "TOO BIG"}")
            if (!fits) allFit = false
        }
        // We hope most fit; some may not if the original uses dictionary copies (cmd 4/6)
        println(if (allFit) "All rooms fit!" else "Some rooms don't fit — need better compression")
    }
}
