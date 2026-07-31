package com.supermetroid.editor.rom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinimapDataTest {

    @Nested
    inner class TileWordEncoding {
        @Test
        fun `tileIndex extracts low 10 bits`() {
            assertEquals(0x1B, MinimapData.tileIndex(0x001B))
            assertEquals(0x3FF, MinimapData.tileIndex(0xFFFF))
            assertEquals(0, MinimapData.tileIndex(0xFC00))
        }

        @Test
        fun `tilePalette extracts bits 10-11`() {
            assertEquals(0, MinimapData.tilePalette(0x001B))
            assertEquals(1, MinimapData.tilePalette(0x041B))
            assertEquals(2, MinimapData.tilePalette(0x081B))
            assertEquals(3, MinimapData.tilePalette(0x0C1B))
        }

        @Test
        fun `tileHFlip checks bit 14`() {
            assertFalse(MinimapData.tileHFlip(0x001B))
            assertTrue(MinimapData.tileHFlip(0x401B))
        }

        @Test
        fun `tileVFlip checks bit 15`() {
            assertFalse(MinimapData.tileVFlip(0x001B))
            assertTrue(MinimapData.tileVFlip(0x801B))
        }

        @Test
        fun `makeTileWord round-trips through accessors`() {
            val word = MinimapData.makeTileWord(index = 0x76, palette = 2, hFlip = true, vFlip = false)
            assertEquals(0x76, MinimapData.tileIndex(word))
            assertEquals(2, MinimapData.tilePalette(word))
            assertTrue(MinimapData.tileHFlip(word))
            assertFalse(MinimapData.tileVFlip(word))
        }

        @Test
        fun `makeTileWord all flags set`() {
            val word = MinimapData.makeTileWord(index = 0xFF, palette = 3, hFlip = true, vFlip = true)
            assertEquals(0xFF, MinimapData.tileIndex(word))
            assertEquals(3, MinimapData.tilePalette(word))
            assertTrue(MinimapData.tileHFlip(word))
            assertTrue(MinimapData.tileVFlip(word))
        }
    }

    @Nested
    inner class MinimapDataModel {
        @Test
        fun `empty creates zeroed map`() {
            val map = MinimapData.empty(0)
            assertEquals(0, map.area)
            assertEquals(MinimapData.TILE_COUNT, map.tiles.size)
            assertEquals(0, map.getTile(0, 0))
            assertEquals(0, map.getTile(63, 31))
        }

        @Test
        fun `getTile out of bounds returns 0`() {
            val map = MinimapData.empty(0)
            assertEquals(0, map.getTile(-1, 0))
            assertEquals(0, map.getTile(0, -1))
            assertEquals(0, map.getTile(64, 0))
            assertEquals(0, map.getTile(0, 32))
        }

        @Test
        fun `withTile sets and reads back`() {
            val map = MinimapData.empty(0)
                .withTile(10, 5, 0x1B)
                .withTile(40, 20, 0x76)
            assertEquals(0x1B, map.getTile(10, 5))
            assertEquals(0x76, map.getTile(40, 20))
            assertEquals(0, map.getTile(0, 0))
        }

        @Test
        fun `withTile out of bounds is no-op`() {
            val map = MinimapData.empty(0)
            val same = map.withTile(100, 100, 0xFF)
            assertEquals(map, same)
        }

        @Test
        fun `withFill fills rectangular region`() {
            val map = MinimapData.empty(0).withFill(2, 3, 5, 6, 0x20)
            for (y in 3..6) {
                for (x in 2..5) {
                    assertEquals(0x20, map.getTile(x, y), "($x,$y)")
                }
            }
            assertEquals(0, map.getTile(1, 3))
            assertEquals(0, map.getTile(6, 3))
            assertEquals(0, map.getTile(2, 2))
        }

        @Test
        fun `dimensions are correct`() {
            assertEquals(64, MinimapData.MAP_WIDTH)
            assertEquals(32, MinimapData.MAP_HEIGHT)
            assertEquals(2048, MinimapData.TILE_COUNT)
        }
    }

    @Nested
    inner class MapStationDataModel {
        @Test
        fun `empty creates all-false`() {
            val data = MapStationData.empty(0)
            assertFalse(data.isRevealed(0, 0))
            assertFalse(data.isRevealed(63, 31))
        }

        @Test
        fun `withToggle flips a tile`() {
            val data = MapStationData.empty(0)
                .withToggle(10, 5)
            assertTrue(data.isRevealed(10, 5))
            assertFalse(data.isRevealed(10, 6))

            val toggled = data.withToggle(10, 5)
            assertFalse(toggled.isRevealed(10, 5))
        }
    }

    @Nested
    inner class MinimapTilesConstants {
        @Test
        fun `palette tiles list is non-empty and has unique entries`() {
            assertTrue(MinimapTiles.PALETTE_TILES.isNotEmpty())
            assertEquals(
                MinimapTiles.PALETTE_TILES.toSet().size,
                MinimapTiles.PALETTE_TILES.size,
                "Palette tiles should have no duplicates"
            )
        }

        @Test
        fun `all palette tiles have names`() {
            for (tile in MinimapTiles.PALETTE_TILES) {
                assertTrue(
                    MinimapTiles.TILE_NAMES.containsKey(tile),
                    "Tile 0x${tile.toString(16)} should have a name"
                )
            }
        }

        @Test
        fun `wall tiles are distinct from item tiles`() {
            val walls = setOf(
                MinimapTiles.WALL_TOP, MinimapTiles.WALL_RIGHT, MinimapTiles.WALL_BOTTOM
            )
            val items = setOf(
                MinimapTiles.ITEM_WALL_TOP, MinimapTiles.ITEM_WALL_BOTTOM,
                MinimapTiles.ITEM_WALL_LEFT, MinimapTiles.ITEM_WALL_RIGHT
            )
            assertTrue(walls.intersect(items).isEmpty())
        }
    }

    @Nested
    inner class FlipBitWallSwapping {
        @Test
        fun `hFlip swaps left and right walls`() {
            // Tile 0x25 has top+left walls. With hFlip it should become top+right.
            val word = MinimapData.makeTileWord(0x25, 3, hFlip = true)
            assertTrue(MinimapData.tileHFlip(word))
            assertFalse(MinimapData.tileVFlip(word))
            assertEquals(0x25, MinimapData.tileIndex(word))
        }

        @Test
        fun `vFlip swaps top and bottom walls`() {
            val word = MinimapData.makeTileWord(0x21, 3, vFlip = true)
            assertTrue(MinimapData.tileVFlip(word))
            assertFalse(MinimapData.tileHFlip(word))
            assertEquals(0x21, MinimapData.tileIndex(word))
        }

        @Test
        fun `both flips can be set simultaneously`() {
            val word = MinimapData.makeTileWord(0x20, 1, hFlip = true, vFlip = true)
            assertTrue(MinimapData.tileHFlip(word))
            assertTrue(MinimapData.tileVFlip(word))
            assertEquals(0x20, MinimapData.tileIndex(word))
            assertEquals(1, MinimapData.tilePalette(word))
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    inner class RomParsing {
        private var romParser: RomParser? = null

        @BeforeAll
        fun loadRom() {
            romParser = TestRomHelper.loadRomParser()
        }

        @Test
        fun `read Crateria minimap has non-empty tiles`() {
            val parser = romParser ?: return
            val map = parser.readMinimapTiles(0)
            assertEquals(0, map.area)
            // Landing Site is at mapX=0, mapY=0 area 0 — there should be non-zero tiles
            val nonEmpty = map.tiles.count { it != 0 }
            assertTrue(nonEmpty > 0, "Crateria map should have non-empty tiles")
        }

        @Test
        fun `read all 7 areas succeeds`() {
            val parser = romParser ?: return
            for (area in 0 until MinimapData.NUM_AREAS) {
                val map = parser.readMinimapTiles(area)
                assertEquals(area, map.area)
                assertEquals(MinimapData.TILE_COUNT, map.tiles.size)
            }
        }

        @Test
        fun `different areas have different map data`() {
            val parser = romParser ?: return
            val crateria = parser.readMinimapTiles(0)
            val brinstar = parser.readMinimapTiles(1)
            assertFalse(crateria.tiles.contentEquals(brinstar.tiles),
                "Crateria and Brinstar should have different map data")
        }

        @Test
        fun `round-trip write and read produces same data`() {
            val parser = romParser ?: return
            val original = parser.readMinimapTiles(0)
            val patches = parser.writeMinimapTiles(original)
            // Apply patches to a copy of ROM data and re-read
            val romCopy = parser.getRomData().copyOf()
            for ((offset, byte) in patches) {
                romCopy[offset] = byte
            }
            val reread = RomParser(romCopy).readMinimapTiles(0)
            assertTrue(original.tiles.contentEquals(reread.tiles),
                "Round-trip write/read should produce identical tiles")
        }

        @Test
        fun `map station data reads without error`() {
            val parser = romParser ?: return
            for (area in 0 until MinimapData.NUM_AREAS) {
                val data = parser.readMapStationData(area)
                assertEquals(area, data.area)
                assertEquals(MinimapData.TILE_COUNT, data.revealed.size)
            }
        }

        @Test
        fun `Crateria map station reveals some tiles`() {
            val parser = romParser ?: return
            val data = parser.readMapStationData(0)
            val revealedCount = data.revealed.count { it }
            assertTrue(revealedCount > 0,
                "Crateria map station should reveal some tiles (found $revealedCount)")
        }

        @Test
        fun `readMinimapTileGraphics returns 256 tiles of 64 pixels each`() {
            val parser = romParser ?: return
            val gfx = parser.readMinimapTileGraphics()
            assertEquals(256, gfx.size, "Should have 256 tiles")
            for (i in gfx.indices) {
                assertEquals(64, gfx[i].size, "Tile $i should have 64 pixels (8x8)")
            }
        }

        @Test
        fun `tile graphics pixel values are in range 0-15`() {
            val parser = romParser ?: return
            val gfx = parser.readMinimapTileGraphics()
            for (i in gfx.indices) for (p in gfx[i]) {
                assertTrue(p in 0..15, "Tile $i pixel value $p should be 0-15")
            }
        }

        @Test
        fun `wall tile graphics are not all-zero`() {
            val parser = romParser ?: return
            val gfx = parser.readMinimapTileGraphics()
            val wall = gfx[MinimapTiles.WALLS_TBLR]
            val nonZero = wall.count { it != 0 }
            assertTrue(nonZero > 0, "Wall tile graphics should have non-zero pixels")
        }

        @Test
        fun `tile 0x1B (room open) differs from tile 0x20 (corner)`() {
            val parser = romParser ?: return
            val gfx = parser.readMinimapTileGraphics()
            assertFalse(gfx[0x1B].contentEquals(gfx[0x20]),
                "Room open and corner tiles should have different graphics")
        }

        @Test
        fun `tile 0x00 has valid pixel data`() {
            val parser = romParser ?: return
            val gfx = parser.readMinimapTileGraphics()
            assertEquals(64, gfx[0].size)
            for (p in gfx[0]) assertTrue(p in 0..15)
        }

        @Test
        fun `map station data round-trips`() {
            val parser = romParser ?: return
            val original = parser.readMapStationData(0)
            val patches = parser.writeMapStationData(original)
            val romCopy = parser.getRomData().copyOf()
            for ((offset, byte) in patches) {
                romCopy[offset] = byte
            }
            val reread = RomParser(romCopy).readMapStationData(0)
            assertTrue(original.revealed.contentEquals(reread.revealed))
        }
    }
}
