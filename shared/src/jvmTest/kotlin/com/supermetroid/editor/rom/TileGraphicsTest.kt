package com.supermetroid.editor.rom

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.TestInstance
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TileGraphicsTest {

    private var romParser: RomParser? = null
    private var gfx: TileGraphics? = null

    @BeforeAll
    fun setUp() {
        romParser = TestRomHelper.loadRomParser()
        romParser?.let { gfx = TileGraphics(it) }
    }

    // ── Tileset loading ──────────────────────────────────────────

    @Nested
    inner class TilesetLoading {
        @Test
        fun `loadTileset succeeds for tileset 0 (Crateria surface)`() {
            val g = gfx ?: return
            assertTrue(g.loadTileset(0))
            assertEquals(0, g.getCachedTilesetId())
        }

        @Test
        fun `loadTileset succeeds for all 29 tilesets`() {
            val g = gfx ?: return
            for (i in 0 until TileGraphics.NUM_TILESETS) {
                g.invalidateCache()
                assertTrue(g.loadTileset(i), "Tileset $i should load")
            }
        }

        @Test
        fun `loadTileset caches and reuses`() {
            val g = gfx ?: return
            g.invalidateCache()
            assertTrue(g.loadTileset(5))
            assertEquals(5, g.getCachedTilesetId())
            assertTrue(g.loadTileset(5))
        }

        @Test
        fun `invalidateCache forces reload`() {
            val g = gfx ?: return
            g.loadTileset(3)
            g.invalidateCache()
            assertEquals(-1, g.getCachedTilesetId())
        }
    }

    // ── Palette parsing ──────────────────────────────────────────

    @Nested
    inner class PaletteTests {
        @Test
        fun `getPalettes returns 8 sub-palettes of 16 colors`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val palettes = g.getPalettes()
            assertNotNull(palettes)
            assertEquals(8, palettes!!.size)
            for (pal in palettes) {
                assertEquals(16, pal.size)
            }
        }

        @Test
        fun `palette colors are opaque ARGB (alpha = 0xFF except index 0)`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val palettes = g.getPalettes()!!
            for (row in palettes.indices) {
                for (col in 1 until 16) {
                    val alpha = (palettes[row][col] ushr 24) and 0xFF
                    assertEquals(0xFF, alpha, "Pal[$row][$col] should be opaque")
                }
            }
        }

        @Test
        fun `getSnesBgr555 and setPaletteEntry round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val testBgr = 0x1234 // arbitrary BGR555 value
            g.setPaletteEntry(2, 5, testBgr)
            val readBack = g.getSnesBgr555(2, 5)
            assertEquals(testBgr, readBack)
        }

        @Test
        fun `getSnesBgr555 out of range returns -1`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(-1, g.getSnesBgr555(-1, 0))
            assertEquals(-1, g.getSnesBgr555(0, 16))
            assertEquals(-1, g.getSnesBgr555(8, 0))
        }

        @Test
        fun `getRawPaletteData returns 256 bytes`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val raw = g.getRawPaletteData()
            assertNotNull(raw)
            assertEquals(8 * 16 * 2, raw!!.size)
        }
    }

    // ── Pixel read/write ─────────────────────────────────────────

    @Nested
    inner class PixelReadWrite {
        @Test
        fun `readPixelIndex returns values 0-15`() {
            val g = gfx ?: return
            g.loadTileset(0)
            for (px in 0..7) for (py in 0..7) {
                val idx = g.readPixelIndex(0, px, py)
                assertTrue(idx in 0..15, "Pixel ($px,$py) of tile 0 should be 0-15, got $idx")
            }
        }

        @Test
        fun `writePixelIndex and readPixelIndex round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val origVal = g.readPixelIndex(1, 3, 5)
            val newVal = (origVal + 1) % 16
            g.writePixelIndex(1, 3, 5, newVal)
            assertEquals(newVal, g.readPixelIndex(1, 3, 5))
            g.writePixelIndex(1, 3, 5, origVal)
            assertEquals(origVal, g.readPixelIndex(1, 3, 5))
        }

        @Test
        fun `readPixelIndex out of range returns -1`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(-1, g.readPixelIndex(-1, 0, 0))
            assertEquals(-1, g.readPixelIndex(1024, 0, 0))
        }

        @Test
        fun `readTileIndices returns 64 values`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val indices = g.readTileIndices(100)
            assertNotNull(indices)
            assertEquals(64, indices!!.size)
            assertTrue(indices.all { it in 0..15 })
        }
    }

    // ── Metatile rendering ───────────────────────────────────────

    @Nested
    inner class MetatileRendering {
        @Test
        fun `renderMetatile returns 256 pixels`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val pixels = g.renderMetatile(0)
            assertNotNull(pixels)
            assertEquals(256, pixels!!.size)
        }

        @Test
        fun `renderMetatile out of range returns null`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertNull(g.renderMetatile(-1))
            assertNull(g.renderMetatile(1024))
        }

        @Test
        fun `renderMetatile returns different data for different metatiles`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val px0 = g.renderMetatile(0)!!
            val px100 = g.renderMetatile(100)!!
            assertFalse(px0.contentEquals(px100), "Metatile 0 and 100 should differ")
        }

        @Test
        fun `getMetatileWords returns 4 sub-tile words`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val words = g.getMetatileWords(0)
            assertNotNull(words)
            assertEquals(4, words!!.size)
        }

        @Test
        fun `getMetatilePalettes returns non-empty set`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val pals = g.getMetatilePalettes(100)
            assertTrue(pals.isNotEmpty())
            assertTrue(pals.all { it in 0..7 })
        }
    }

    // ── Metatile pixel operations ────────────────────────────────

    @Nested
    inner class MetatilePixelOps {
        @Test
        fun `readMetatilePixel returns values 0-15`() {
            val g = gfx ?: return
            g.loadTileset(0)
            for (px in 0..15) for (py in 0..15) {
                val idx = g.readMetatilePixel(50, px, py)
                assertTrue(idx in 0..15, "metatile pixel ($px,$py)")
            }
        }

        @Test
        fun `writeMetatilePixel and readMetatilePixel round-trip`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val orig = g.readMetatilePixel(50, 4, 4)
            val newVal = (orig + 1) % 16
            g.writeMetatilePixel(50, 4, 4, newVal)
            assertEquals(newVal, g.readMetatilePixel(50, 4, 4))
            g.writeMetatilePixel(50, 4, 4, orig)
        }

        @Test
        fun `metatilePixelToTileCoords maps to valid tile`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val coords = g.metatilePixelToTileCoords(50, 10, 12)
            assertNotNull(coords)
            val (tileNum, tx, ty) = coords!!
            assertTrue(tileNum in 0 until TileGraphics.TOTAL_TILES)
            assertTrue(tx in 0..7)
            assertTrue(ty in 0..7)
        }

        @Test
        fun `getMetatilePixelPaletteRow returns 0-7`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val row = g.getMetatilePixelPaletteRow(50, 4, 4)
            assertTrue(row in 0..7)
        }
    }

    // ── Tileset grid rendering ───────────────────────────────────

    @Nested
    inner class GridRendering {
        @Test
        fun `renderTilesetGrid returns 512x512 grid`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val grid = g.renderTilesetGrid()
            assertNotNull(grid)
            assertEquals(512, grid!!.width)
            assertEquals(512, grid.height)
            assertEquals(32, grid.gridCols)
            assertEquals(32, grid.gridRows)
            assertEquals(512 * 512, grid.pixels.size)
        }

        @Test
        fun `renderTilesetGrid without load returns null`() {
            val parser = romParser ?: return
            val fresh = TileGraphics(parser)
            assertNull(fresh.renderTilesetGrid())
        }
    }

    // ── Tile sheet render/import round-trip ───────────────────────

    @Nested
    inner class TileSheetRoundTrip {
        @Test
        fun `renderTileSheet returns correct dimensions`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val result = g.renderTileSheet(0, 64, cols = 16)
            assertNotNull(result)
            val (pixels, w, h) = result!!
            assertEquals(128, w) // 16 * 8
            assertEquals(32, h) // 4 * 8
            assertEquals(w * h, pixels.size)
        }

        @Test
        fun `buildTilePaletteMap returns 1024 entries`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val map = g.buildTilePaletteMap()
            assertEquals(TileGraphics.TOTAL_TILES, map.size)
            assertTrue(map.all { it in 0..7 })
        }
    }

    // ── CRE / Variable split ─────────────────────────────────────

    @Nested
    inner class CreVariableSplit {
        @Test
        fun `getCreOffset returns 640 for normal tilesets`() {
            val g = gfx ?: return
            g.loadTileset(0)
            assertEquals(640, g.getCreOffset())
            assertEquals(640, g.getVarTileCount())
            assertEquals(384, g.getCreTileCount())
        }

        @Test
        fun `getCreOffset returns 1024 for Kraid tileset (no CRE overlay)`() {
            val g = gfx ?: return
            g.loadTileset(TileGraphics.KRAID_TILESET)
            // SMILE places CRE at 0x8000 for Kraid, effectively no overlay
            assertEquals(1024, g.getCreOffset())
            assertEquals(1024, g.getVarTileCount())
            assertEquals(0, g.getCreTileCount())
        }

        @Test
        fun `getCreOffset returns 640 for Ceres tileset (CRE overlays at 0x5000)`() {
            val g = gfx ?: return
            g.loadTileset(17) // Ceres tileset — CRE always overlays per SMILE behavior
            assertEquals(640, g.getCreOffset())
            assertEquals(640, g.getVarTileCount())
            assertEquals(384, g.getCreTileCount())
        }

        @Test
        fun `getRawVarGfx and getRawCreGfx return valid data`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val varGfx = g.getRawVarGfx()
            val creGfx = g.getRawCreGfx()
            assertNotNull(varGfx)
            assertNotNull(creGfx)
            assertTrue(varGfx!!.isNotEmpty())
            assertTrue(creGfx!!.isNotEmpty())
        }

        @Test
        fun `applyCustomVarGfx modifies variable tiles`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val orig = g.getRawVarGfx()!!.copyOf()
            val custom = orig.copyOf()
            custom[0] = (custom[0].toInt() xor 0xFF).toByte()
            g.applyCustomVarGfx(custom)
            val after = g.getRawVarGfx()!!
            assertEquals(custom[0], after[0])
            g.applyCustomVarGfx(orig)
        }
    }

    // ── Palette image rendering ──────────────────────────────────

    @Nested
    inner class PaletteImage {
        @Test
        fun `renderPaletteImage returns correct dimensions`() {
            val g = gfx ?: return
            g.loadTileset(0)
            val result = g.renderPaletteImage(cellSize = 8)
            assertNotNull(result)
            val (pixels, w, h) = result!!
            assertEquals(128, w)
            assertEquals(64, h)
            assertEquals(w * h, pixels.size)
        }
    }
}
