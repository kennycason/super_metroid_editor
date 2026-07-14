package com.supermetroid.editor.ui

/**
 * A brush can be 1×1 (single tile) or NxM (rectangle from tileset).
 * tiles[row][col] = metatile index. hFlip/vFlip apply to the whole grid.
 *
 * Per-tile overrides allow different block types and BTS values within
 * a multi-tile brush (e.g., shot block + H-extend pairs).
 */
data class TileBrush(
    val tiles: List<List<Int>>,  // [row][col] of metatile indices
    val blockType: Int = 0x8,
    val hFlip: Boolean = false,
    val vFlip: Boolean = false,
    val blockTypeOverrides: Map<Long, Int> = emptyMap(),
    val btsOverrides: Map<Long, Int> = emptyMap(),
    val flipOverrides: Map<Long, Int> = emptyMap(),  // per-tile: bit0=hflip, bit1=vflip
    val plmOverrides: Map<Long, Pair<Int, Int>> = emptyMap(),  // per-tile: (plmId, plmParam)
    val skipCells: Set<Long> = emptySet()  // cells to skip when painting (null/empty pattern cells)
) {
    val cols get() = tiles.firstOrNull()?.size ?: 0
    val rows get() = tiles.size

    private fun key(r: Int, c: Int) = (r.toLong() shl 32) or (c.toLong() and 0xFFFFFFFFL)

    fun blockTypeAt(r: Int, c: Int): Int = blockTypeOverrides[key(r, c)] ?: blockType
    fun btsAt(r: Int, c: Int): Int = btsOverrides[key(r, c)] ?: 0
    fun plmAt(r: Int, c: Int): Pair<Int, Int>? = plmOverrides[key(r, c)]

    /** Per-tile flip state: original flips XOR'd with brush-level flips. */
    fun tileHFlip(r: Int, c: Int): Boolean {
        val perTile = (flipOverrides[key(r, c)] ?: 0) and 1 != 0
        return perTile xor hFlip
    }
    fun tileVFlip(r: Int, c: Int): Boolean {
        val perTile = (flipOverrides[key(r, c)] ?: 0) and 2 != 0
        return perTile xor vFlip
    }

    /** Encode one tile at (r, c) as a 16-bit block word. */
    fun blockWordAt(r: Int, c: Int): Int {
        val idx = tiles.getOrNull(r)?.getOrNull(c) ?: return 0
        var word = idx and 0x3FF
        if (tileHFlip(r, c)) word = word or (1 shl 10)
        if (tileVFlip(r, c)) word = word or (1 shl 11)
        word = word or ((blockTypeAt(r, c) and 0xF) shl 12)
        return word
    }

    /** For display: the first tile's metatile index. */
    val primaryIndex get() = tiles.firstOrNull()?.firstOrNull() ?: 0

    companion object {
        fun single(metatileIndex: Int, blockType: Int = 0x8, bts: Int = 0): TileBrush {
            val btsMap = if (bts != 0) mapOf(0L to bts) else emptyMap()
            return TileBrush(
                tiles = listOf(listOf(metatileIndex)),
                blockType = blockType,
                btsOverrides = btsMap
            )
        }
    }
}

enum class EditorTool { PAINT, FILL, SAMPLE, SELECT, ERASE }

enum class RoomEditLayer {
    LAYER1,
    LAYER2,
}
