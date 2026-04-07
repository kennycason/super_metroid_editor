package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.MinimapData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MinimapEditorStateTest {

    @Nested
    inner class FloodFill {
        @Test
        fun `flood fill replaces connected tiles with same value`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            // Create a 3x3 block of tile 0x1B at position (5,5)
            for (y in 5..7) for (x in 5..7) tiles[y * MinimapData.MAP_WIDTH + x] = 0x1B
            val data = MinimapData(0, tiles)
            val result = floodFillMinimap(data, 5, 5, 0x1B, 0x20)
            // All 9 tiles should be replaced
            for (y in 5..7) for (x in 5..7) assertEquals(0x20, result.getTile(x, y))
            // Adjacent tile should NOT be replaced
            assertEquals(0, result.getTile(4, 5))
        }

        @Test
        fun `flood fill does not replace different tiles`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[5 * MinimapData.MAP_WIDTH + 5] = 0x1B
            tiles[5 * MinimapData.MAP_WIDTH + 6] = 0x20 // different
            tiles[5 * MinimapData.MAP_WIDTH + 7] = 0x1B
            val data = MinimapData(0, tiles)
            val result = floodFillMinimap(data, 5, 5, 0x1B, 0xFF)
            assertEquals(0xFF, result.getTile(5, 5))
            assertEquals(0x20, result.getTile(6, 5)) // barrier
            assertEquals(0x1B, result.getTile(7, 5)) // disconnected
        }

        @Test
        fun `flood fill with same target and replacement is no-op`() {
            val tiles = IntArray(MinimapData.TILE_COUNT)
            tiles[0] = 0x1B
            val data = MinimapData(0, tiles)
            // In practice the caller guards this, but test the function directly
            val result = floodFillMinimap(data, 0, 0, 0x1B, 0x1B)
            assertEquals(0x1B, result.getTile(0, 0))
        }
    }

    @Nested
    inner class TileWordEncoding {
        @Test
        fun `makeTileWord encodes tile index and palette correctly`() {
            val word = MinimapData.makeTileWord(0x1B, 3)
            assertEquals(0x1B, MinimapData.tileIndex(word))
            assertEquals(3, MinimapData.tilePalette(word))
        }

        @Test
        fun `flip bits are extracted correctly`() {
            val word = 0xC01B // hflip + vflip + tile 0x1B
            assertEquals(true, MinimapData.tileHFlip(word))
            assertEquals(true, MinimapData.tileVFlip(word))
            assertEquals(0x1B, MinimapData.tileIndex(word))
        }

        @Test
        fun `no flip bits when not set`() {
            val word = MinimapData.makeTileWord(0x20, 1)
            assertEquals(false, MinimapData.tileHFlip(word))
            assertEquals(false, MinimapData.tileVFlip(word))
        }
    }

    @Nested
    inner class MinimapDataModel {
        @Test
        fun `withTile creates new data with changed tile`() {
            val data = MinimapData.empty(0)
            val updated = data.withTile(10, 5, 0x1B)
            assertEquals(0x1B, updated.getTile(10, 5))
            assertEquals(0, data.getTile(10, 5)) // original unchanged
        }

        @Test
        fun `getTile returns 0 for out of bounds`() {
            val data = MinimapData.empty(0)
            assertEquals(0, data.getTile(-1, 0))
            assertEquals(0, data.getTile(0, -1))
            assertEquals(0, data.getTile(MinimapData.MAP_WIDTH, 0))
            assertEquals(0, data.getTile(0, MinimapData.MAP_HEIGHT))
        }
    }
}
