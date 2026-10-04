package com.supermetroid.editor.rom

/**
 * Stateless decoder for the SNES planar tile formats used by Super Metroid.
 *
 * Standard 4bpp tiles store interleaved bitplanes 0/1 in bytes 0..15 and
 * bitplanes 2/3 in bytes 16..31. The Ceres elevator/Ridley tileset payloads
 * instead split the odd and even planes into two global halves; that layout is
 * exposed explicitly so callers cannot accidentally treat it as standard 4bpp.
 */
class TileDecoder {

    /** Decode one standard SNES 4bpp tile as an 8×8 row/column array. */
    fun decodeTile(tileData: ByteArray, offset: Int = 0): Array<IntArray> {
        val indices = decode4bppTileIndices(tileData, offset)
        return Array(8) { row -> IntArray(8) { column -> indices[row * 8 + column] } }
    }

    /** Decode one standard SNES 4bpp tile to 64 row-major colour indices (0..15). */
    fun decode4bppTileIndices(tileData: ByteArray, offset: Int = 0): IntArray {
        requireRange(tileData, offset, RomConstants.BYTES_PER_4BPP_TILE, "4bpp tile")
        return decode4bppRows { row ->
            val rowOffset = offset + row * 2
            TileBitplanes(
                bp0 = tileData[rowOffset].u8(),
                bp1 = tileData[rowOffset + 1].u8(),
                bp2 = tileData[rowOffset + 16].u8(),
                bp3 = tileData[rowOffset + 17].u8(),
            )
        }
    }

    /**
     * Decode one tile from the global split-plane 4bpp layout used by tilesets
     * $11..$14. The first half stores bp0/bp2; the second stores bp1/bp3.
     */
    fun decodeSplitPlane4bppTileIndices(
        tileData: ByteArray,
        tileIndex: Int,
        tileCount: Int = tileData.size / RomConstants.BYTES_PER_4BPP_TILE,
    ): IntArray {
        require(tileCount > 0) { "split-plane tile count must be positive" }
        require(tileData.size == tileCount * RomConstants.BYTES_PER_4BPP_TILE) {
            "split-plane data has ${tileData.size} bytes; expected ${tileCount * RomConstants.BYTES_PER_4BPP_TILE}"
        }
        require(tileIndex in 0 until tileCount) {
            "split-plane tile index $tileIndex is outside 0 until $tileCount"
        }
        val half = tileData.size / 2
        val lowOffset = tileIndex * 16
        val highOffset = half + tileIndex * 16
        return decode4bppRows { row ->
            TileBitplanes(
                bp0 = tileData[lowOffset + row].u8(),
                bp1 = tileData[highOffset + row].u8(),
                bp2 = tileData[lowOffset + 8 + row].u8(),
                bp3 = tileData[highOffset + 8 + row].u8(),
            )
        }
    }

    /** Decode one standard SNES 2bpp tile to 64 row-major colour indices (0..3). */
    fun decode2bppTileIndices(tileData: ByteArray, offset: Int = 0): IntArray {
        requireRange(tileData, offset, 16, "2bpp tile")
        val pixels = IntArray(64)
        for (row in 0 until 8) {
            val bp0 = tileData[offset + row * 2].u8()
            val bp1 = tileData[offset + row * 2 + 1].u8()
            for (column in 0 until 8) {
                val bit = 7 - column
                pixels[row * 8 + column] =
                    ((bp0 ushr bit) and 1) or (((bp1 ushr bit) and 1) shl 1)
            }
        }
        return pixels
    }

    /** Decode as many complete standard 4bpp tiles as requested. */
    fun decodeTileset(tilesetData: ByteArray, numTiles: Int): List<Array<IntArray>> {
        require(numTiles >= 0) { "tile count must not be negative" }
        return buildList {
            for (tile in 0 until numTiles) {
                val offset = tile * RomConstants.BYTES_PER_4BPP_TILE
                if (offset + RomConstants.BYTES_PER_4BPP_TILE <= tilesetData.size) {
                    add(decodeTile(tilesetData, offset))
                }
            }
        }
    }

    private fun decode4bppRows(rowAt: (Int) -> TileBitplanes): IntArray {
        val pixels = IntArray(64)
        for (row in 0 until 8) {
            val planes = rowAt(row)
            for (column in 0 until 8) {
                val bit = 7 - column
                pixels[row * 8 + column] =
                    ((planes.bp0 ushr bit) and 1) or
                    (((planes.bp1 ushr bit) and 1) shl 1) or
                    (((planes.bp2 ushr bit) and 1) shl 2) or
                    (((planes.bp3 ushr bit) and 1) shl 3)
            }
        }
        return pixels
    }

    private fun requireRange(data: ByteArray, offset: Int, size: Int, label: String) {
        require(offset >= 0 && offset <= data.size - size) {
            "$label range $offset..${offset + size - 1} is outside ${data.size} bytes"
        }
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private data class TileBitplanes(
        val bp0: Int,
        val bp1: Int,
        val bp2: Int,
        val bp3: Int,
    )
}
