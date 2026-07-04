package com.supermetroid.editor.procgen

/**
 * Read-only view over decompressed level data for structural analysis:
 * [2-byte layer-1 size LE][layer-1 block words][BTS bytes][optional layer 2].
 *
 * Resolves H-extend (0x5, copies the block to its left) and V-extend
 * (0xD, copies the block above) so block types can be classified uniformly.
 */
class LevelGrid private constructor(
    val width: Int,
    val height: Int,
    private val words: IntArray,
    private val btsBytes: IntArray,
) {
    fun word(x: Int, y: Int): Int = words[y * width + x]
    fun bts(x: Int, y: Int): Int = btsBytes[y * width + x]
    fun type(x: Int, y: Int): Int = (word(x, y) shr 12) and 0xF

    /** Block type with H/V-extend chains resolved to their anchor's type. */
    fun resolvedType(x: Int, y: Int): Int {
        var cx = x
        var cy = y
        // Extend chains are short in practice; bound the walk defensively.
        repeat(32) {
            when (type(cx, cy)) {
                0x5 -> if (cx > 0) cx-- else return 0x8
                0xD -> if (cy > 0) cy-- else return 0x8
                else -> return type(cx, cy)
            }
        }
        return type(cx, cy)
    }

    companion object {
        /** Parse raw decompressed level data; null if too small for the room size. */
        fun parse(data: ByteArray, width: Int, height: Int): LevelGrid? {
            if (data.size < 2) return null
            val layer1Size = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
            val cellCount = width * height
            if (layer1Size / 2 < cellCount) return null
            if (2 + cellCount * 2 > data.size) return null
            val words = IntArray(cellCount)
            for (i in 0 until cellCount) {
                val off = 2 + i * 2
                words[i] = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
            }
            val btsBytes = IntArray(cellCount)
            val btsBase = 2 + layer1Size
            for (i in 0 until cellCount) {
                val off = btsBase + i
                btsBytes[i] = if (off < data.size) data[off].toInt() and 0xFF else 0
            }
            return LevelGrid(width, height, words, btsBytes)
        }
    }
}
